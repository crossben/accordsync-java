package io.github.crossben.accordsync.fleet;

import static io.github.crossben.accordsync.fleet.Fleet.SCHEMA;
import static io.github.crossben.accordsync.fleet.Fleet.SERVER_URL;
import static io.github.crossben.accordsync.fleet.Fleet.TsDevice.cmd;
import static io.github.crossben.accordsync.fleet.Fleet.snapshotOf;
import static io.github.crossben.accordsync.fleet.Fleet.tokenFor;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.crossben.accordsync.client.AccordClient;
import io.github.crossben.accordsync.client.HttpTransport;
import io.github.crossben.accordsync.client.MemoryStorage;
import io.github.crossben.accordsync.core.JsonBool;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.fleet.Fleet.FlakyTransport;
import io.github.crossben.accordsync.fleet.Fleet.TsDevice;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The mixed fleet: Java devices and TypeScript devices work on the same records through the real
 * server over a network that loses requests and responses, with a compaction mid-run. After the
 * network heals, every device must hold byte-identical canonical snapshots.
 * Port of python/interop/tests/test_mixed_fleet.py.
 */
class MixedFleetTest {
    private static final double LOSS = 0.25;
    private static final List<String> RECORDS = List.of("dossier:1", "dossier:2", "dossier:é");
    private static final Object[] STATUSES = {"s0", "s1", null, 2.5, "é", "", "😀", 1e21};

    @BeforeAll
    static void server() {
        Fleet.requireServer();
    }

    static Stream<Long> seeds() {
        String raw = System.getenv().getOrDefault("ACCORD_INTEROP_SEEDS", "1,2,3");
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Long::valueOf);
    }

    private record Edit(String cmd, String field, Object value) {}

    private static Edit edit(Random rng) {
        switch (rng.nextInt(7)) {
            case 0:
                return new Edit("inc", "visits", (long) rng.nextInt(9) - 3);
            case 1:
            case 2: {
                String cmd = rng.nextBoolean() ? "add" : "remove";
                double kind = rng.nextDouble();
                Object element = kind < 0.45 ? "doc-" + rng.nextInt(4) : kind < 0.9 ? (Object) (long) rng.nextInt(3) : "😀";
                return new Edit(cmd, "docs", element);
            }
            case 3:
                return new Edit("assign", "status", STATUSES[rng.nextInt(STATUSES.length)]);
            case 4: {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("n", rng.nextInt(9));
                v.put("10", true);
                v.put("a", 1e21);
                return new Edit("assign", "client_name", v);
            }
            default:
                return new Edit("sync", "", null);
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void javaAndTypeScriptDevicesConverge(long seed) {
        Fleet.resetServer();
        Random rng = new Random(seed);
        String awa = tokenFor("awa", List.of("dakar"));
        String moussa = tokenFor("moussa", List.of("dakar"));

        List<FlakyTransport> networks = new ArrayList<>();
        List<AccordClient> java = new ArrayList<>();
        List<TsDevice> ts = new ArrayList<>();

        try {
            for (String[] d : new String[][] {{"java-awa", awa}, {"java-moussa", moussa}}) {
                String token = d[1];
                FlakyTransport net = new FlakyTransport(
                        new HttpTransport(SERVER_URL, () -> token), new Random(seed * 31 + networks.size()), LOSS);
                networks.add(net);
                java.add(AccordClient.open(AccordClient.options()
                        .schema(SCHEMA).storage(new MemoryStorage()).deviceId(d[0]).transport(net)));
            }
            ts.add(new TsDevice("ts-awa", awa, seed * 7 + 1, LOSS));
            ts.add(new TsDevice("ts-moussa", moussa, seed * 7 + 2, LOSS));

            Runnable settle = () -> {
                for (FlakyTransport n : networks) n.loss = 0;
                for (TsDevice t : ts) t.call(cmd("heal"));
                for (int round = 0; round < 3; round++) {
                    for (AccordClient d : java) d.sync();
                    for (TsDevice t : ts) {
                        JsonObject answer = t.call(cmd("sync"));
                        assertThat(answer.get("ok")).as("ts sync: %s", answer).isEqualTo(JsonBool.TRUE);
                    }
                }
            };

            // Every record starts in the shared zone, written by both kinds of device.
            java.get(0).assign(RECORDS.get(0), "zone", "dakar");
            ts.get(0).call(cmd("assign", "record", RECORDS.get(1), "field", "zone", "value", "dakar"));
            java.get(1).assign(RECORDS.get(2), "zone", "dakar");

            long compacted = 0;
            for (int step = 0; step < 150; step++) {
                String record = RECORDS.get(rng.nextInt(RECORDS.size()));
                Edit e = edit(rng);
                int i = rng.nextInt(4);
                if (i < 2) {
                    AccordClient d = java.get(i);
                    switch (e.cmd()) {
                        case "inc" -> d.inc(record, e.field(), (Long) e.value());
                        case "add" -> d.add(record, e.field(), e.value());
                        case "remove" -> d.remove(record, e.field(), e.value());
                        case "assign" -> d.assign(record, e.field(), e.value());
                        default -> {
                            try {
                                d.sync();
                            } catch (UncheckedIOException lost) {
                                // The lossy network: the outbox is retried next round.
                            }
                        }
                    }
                } else {
                    ts.get(i - 2).call("sync".equals(e.cmd())
                            ? cmd("sync")
                            : cmd(e.cmd(), "record", record, "field", e.field(), "value", e.value()));
                }
                if (step == 75) {
                    // Compaction folds only what every live device has pulled (its watermark is the
                    // lowest device cursor), so settle the fleet first, then go back to the lossy net.
                    settle.run();
                    compacted = Fleet.compactServer();
                    assertThat(compacted).as("records folded by the mid-run compaction").isPositive();
                    for (FlakyTransport n : networks) n.loss = LOSS;
                    for (TsDevice t : ts) t.call(cmd("loss", "loss", LOSS));
                }
            }

            // Heal the network, then sync everyone until nothing is pending and all are current.
            settle.run();

            Map<String, String> snapshots = new LinkedHashMap<>();
            for (AccordClient d : java) snapshots.put(d.deviceId(), snapshotOf(d));
            for (int k = 0; k < ts.size(); k++) snapshots.put("ts-" + k, ts.get(k).snapshot());
            assertThat(snapshots.values().stream().distinct().count())
                    .as("seed %d (%d records compacted mid-run): %s", seed, compacted, snapshots)
                    .isEqualTo(1);
            assertThat(snapshots.values().iterator().next()).contains("dossier:é");
            for (AccordClient d : java) assertThat(d.status().pending()).isZero();
            for (TsDevice t : ts) assertThat(t.pending()).isZero();
        } finally {
            for (TsDevice t : ts) t.close();
            for (AccordClient d : java) d.close();
        }
    }
}
