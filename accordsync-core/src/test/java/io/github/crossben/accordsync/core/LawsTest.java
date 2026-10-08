package io.github.crossben.accordsync.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Strategy laws against reference models (port of laws.test.ts, with a seeded generator).
 * Runs: {@code -Daccord.propertyRuns=N} (default 300).
 */
class LawsTest {
    static final int RUNS = Integer.getInteger("accord.propertyRuns", 300);
    static final Schema SCHEMA = Schema.define(Map.of("dossier", Map.of(
            "name", Strategy.lww(), "docs", Strategy.set(), "visits", Strategy.counter(), "status", Strategy.conflict())));
    static final String[] FIELDS = {"name", "docs", "visits", "status"};

    record Scenario(List<LocalWriter> devs, List<Op> all, long[] shuffleSeeds) {}

    static Scenario scenario(Random rnd) {
        int devices = 2 + rnd.nextInt(3);
        long[] tick = {1_700_000_000_000L};
        List<LocalWriter> devs = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            long skew = rnd.nextInt(7_200_001) - 3_600_000;
            devs.add(new LocalWriter(SCHEMA, "d" + i, () -> (tick[0] += 7) + skew));
        }
        List<Op> all = new ArrayList<>();
        for (int n = rnd.nextInt(61); n > 0; n--) {
            if (rnd.nextBoolean()) {
                LocalWriter w = devs.get(rnd.nextInt(devices));
                String rec = "dossier:" + rnd.nextInt(2);
                String field = FIELDS[rnd.nextInt(4)];
                int v = rnd.nextInt(7) - 3;
                switch (field) {
                    case "name" -> all.add(w.assign(rec, field, "n" + v));
                    case "status" -> all.add(w.assign(rec, field, "s" + v));
                    case "visits" -> all.add(w.inc(rec, field, v));
                    default -> {
                        if (rnd.nextBoolean()) all.add(w.remove(rec, field, "doc" + Math.abs(v) % 3));
                        else all.add(w.add(rec, field, "doc" + Math.abs(v) % 3));
                    }
                }
            } else {
                LocalWriter from = devs.get(rnd.nextInt(devices));
                LocalWriter to = devs.get(rnd.nextInt(devices));
                int mask = rnd.nextInt();
                List<Op> log = from.replica().ops();
                for (int i = 0; i < log.size(); i++) if (((mask >> (i % 31)) & 1) != 0) to.receive(log.get(i));
            }
        }
        return new Scenario(devs, all, new long[] {rnd.nextLong(), rnd.nextLong(), rnd.nextLong()});
    }

    static <T> List<T> shuffled(List<T> xs, long seed) {
        List<T> out = new ArrayList<>(xs);
        java.util.Collections.shuffle(out, new Random(seed));
        return out;
    }

    static Replica replay(List<Op> ops) {
        Replica r = new Replica(SCHEMA);
        for (Op op : ops) r.apply(op);
        return r;
    }

    /** Reference models, computed straight from the definitions over the full op set. */
    static JsonObject model(List<Op> all, String record) {
        Map<String, JsonValue> out = new LinkedHashMap<>();
        AssignOp lww = null;
        long visits = 0;
        Set<String> removedTags = new HashSet<>();
        Set<String> superseded = new HashSet<>();
        for (Op o : all) {
            if (!o.record().equals(record)) continue;
            if (o.field().equals("name") && (lww == null || Hlc.compare(o.hlc(), lww.hlc()) > 0)) lww = (AssignOp) o;
            if (o instanceof IncOp i) visits += i.by();
            if (o instanceof AddOp a) removedTags.addAll(a.deps());
            if (o instanceof RemoveOp r) removedTags.addAll(r.deps());
            if (o.field().equals("status")) superseded.addAll(((AssignOp) o).deps());
        }
        Set<String> docs = new java.util.TreeSet<>();
        Map<String, JsonValue> live = new java.util.TreeMap<>(); // op ids are ASCII here
        for (Op o : all) {
            if (!o.record().equals(record)) continue;
            if (o instanceof AddOp a && !removedTags.contains(a.opId())) docs.add(((JsonString) a.element()).value());
            if (o.field().equals("status") && !superseded.contains(o.opId())) live.put(o.opId(), ((AssignOp) o).value());
        }
        if (lww != null) out.put("name", lww.value());
        out.put("docs", Json.of(new ArrayList<>(docs)));
        out.put("visits", JsonNumber.of(visits));
        if (live.size() == 1) out.put("status", Json.of(Map.of("value", live.values().iterator().next())));
        if (live.size() > 1) {
            List<Object> c = new ArrayList<>();
            live.forEach((id, v) -> c.add(Map.of("value", v, "opId", id)));
            out.put("status", Json.of(Map.of("conflicted", c)));
        }
        return new JsonObject(out);
    }

    @Test
    void anyDeliveryOrderWithDuplicatesReadsTheSameState() {
        for (int run = 0; run < RUNS; run++) {
            Scenario s = scenario(new Random(run));
            String reference = replay(s.all()).snapshot();
            for (long seed : s.shuffleSeeds()) {
                List<Op> withDuplicates = new ArrayList<>(s.all());
                withDuplicates.addAll(s.all().subList(0, Math.min(s.all().size(), (int) Math.floorMod(seed, 5L))));
                assertThat(replay(shuffled(withDuplicates, seed)).snapshot()).as("run %d", run).isEqualTo(reference);
            }
        }
    }

    @Test
    void devicesConvergeOnceEveryOpIsDelivered() {
        for (int run = 0; run < RUNS; run++) {
            Scenario s = scenario(new Random(1_000_000L + run));
            for (LocalWriter d : s.devs()) for (Op op : shuffled(s.all(), s.shuffleSeeds()[0])) d.receive(op);
            String reference = replay(s.all()).snapshot();
            for (LocalWriter d : s.devs()) assertThat(d.replica().snapshot()).as("run %d", run).isEqualTo(reference);
        }
    }

    @Test
    void eachStrategyMatchesItsDefinition() {
        for (int run = 0; run < RUNS; run++) {
            Scenario s = scenario(new Random(2_000_000L + run));
            Replica r = replay(shuffled(s.all(), s.shuffleSeeds()[1]));
            for (String record : r.records()) {
                assertThat(r.read(record)).as("run %d %s", run, record).contains(model(s.all(), record));
            }
        }
    }

    @Test
    void compactionASnapshotThroughJsonPlusLaterOpsReadsLikeTheWholeLog() {
        for (int run = 0; run < RUNS; run++) {
            Random rnd = new Random(3_000_000L + run);
            List<Op> ops = scenario(rnd).all();
            // Ops in creation order are causally ordered, so every prefix is causally closed.
            int cut = rnd.nextInt(ops.size() + 1);
            Replica before = replay(ops.subList(0, cut));
            Replica compacted = new Replica(SCHEMA);
            for (String record : before.records()) {
                String text = Json.canonical(before.snapshotRecord(record).toJson());
                compacted.loadSnapshot(RecordSnapshot.fromJson(Json.parse(text)));
            }
            for (Op op : ops.subList(cut, ops.size())) compacted.apply(op);
            assertThat(compacted.snapshot()).as("run %d, cut %d", run, cut).isEqualTo(replay(ops).snapshot());
        }
    }

    @Test
    void aConflictFieldWrittenConcurrentlyIsNeverAutoResolved() {
        Random rnd = new Random(7);
        for (int run = 0; run < RUNS; run++) {
            String x = "x" + rnd.nextInt(1000);
            String y = "y" + rnd.nextInt(1000);
            long[] t = {0};
            LocalWriter a = new LocalWriter(SCHEMA, "a", () -> t[0]++);
            LocalWriter b = new LocalWriter(SCHEMA, "b", () -> t[0]++);
            AssignOp p = a.assign("dossier:1", "status", x);
            AssignOp q = b.assign("dossier:1", "status", y);
            a.receive(q);
            b.receive(p);
            for (LocalWriter w : List.of(a, b)) assertThat(w.replica().conflicts()).containsExactly(new FieldRef("dossier:1", "status"));
        }
    }

    @Test
    void elementsDedupeByJsonValue() {
        assertThat(new LinkedHashSet<>(List.of(JsonNumber.of(1), JsonNumber.of(1.0), new JsonString("1")))).hasSize(2);
    }
}
