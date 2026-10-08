package io.github.crossben.accordsync.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** The shared contract: golden vectors, random vectors and op-hash vectors from the TypeScript core. */
class VectorsTest {
    static <T> List<List<T>> permutations(List<T> xs) {
        if (xs.size() <= 1) return List.of(xs);
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < xs.size(); i++) {
            List<T> rest = new ArrayList<>(xs);
            T x = rest.remove(i);
            for (List<T> p : permutations(rest)) {
                List<T> q = new ArrayList<>();
                q.add(x);
                q.addAll(p);
                out.add(q);
            }
        }
        return out;
    }

    @TestFactory
    Stream<DynamicTest> goldenVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String file : List.of("conflict.json", "counter.json", "lww.json", "set.json")) {
            JsonObject v = Contract.read("vectors/" + file);
            assertThat(v.get("version")).isEqualTo(JsonNumber.of(1));
            Schema schema = Contract.schema((JsonObject) v.get("schema"));
            for (JsonValue c : ((JsonArray) v.get("cases")).items()) {
                JsonObject cs = (JsonObject) c;
                tests.add(DynamicTest.dynamicTest(file + ": " + Contract.str(cs.get("name")), () -> {
                    List<Op> ops = new ArrayList<>();
                    for (JsonValue o : ((JsonArray) cs.get("ops")).items()) ops.add(Wire.decode(o));
                    String expected = Json.canonical(cs.get("expected"));
                    for (List<Op> order : permutations(ops)) {
                        Replica r = new Replica(schema);
                        for (Op op : order) r.apply(op);
                        for (Op op : order) assertThat(r.apply(op)).isEqualTo(ApplyResult.DUPLICATE);
                        assertThat(r.snapshot()).isEqualTo(expected);
                    }
                }));
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> randomVectors() {
        JsonObject file = Contract.read("vectors/random/cases.json");
        Schema schema = Contract.schema((JsonObject) file.get("schema"));
        List<JsonValue> cases = ((JsonArray) file.get("cases")).items();
        assertThat(cases).hasSize(40);
        return cases.stream().map(c -> {
            JsonObject cs = (JsonObject) c;
            return DynamicTest.dynamicTest("seed " + Json.canonical(cs.get("seed")), () -> {
                List<Op> ops = new ArrayList<>();
                for (JsonValue o : ((JsonArray) cs.get("ops")).items()) {
                    Op op = Wire.decode(o);
                    assertThat(Json.canonical(Wire.encode(op))).isEqualTo(Json.canonical(o));
                    ops.add(op);
                }
                String snapshot = Contract.str(cs.get("snapshot"));
                JsonObject records = (JsonObject) cs.get("records");

                Replica inOrder = new Replica(schema);
                for (Op op : ops) inOrder.apply(op);
                assertThat(inOrder.snapshot()).isEqualTo(snapshot);
                assertThat(inOrder.records()).containsExactlyInAnyOrderElementsOf(records.members().keySet());
                for (var e : records.members().entrySet()) {
                    assertThat(Json.canonical(inOrder.snapshotRecord(e.getKey()).toJson()))
                            .as(e.getKey()).isEqualTo(Contract.str(e.getValue()));
                }

                Random rnd = new Random(((JsonNumber) cs.get("seed")).longValue());
                for (int s = 0; s < 5; s++) {
                    List<Op> order = new ArrayList<>(ops);
                    for (int d = rnd.nextInt(5); d > 0 && !ops.isEmpty(); d--) order.add(ops.get(rnd.nextInt(ops.size())));
                    Collections.shuffle(order, rnd);
                    Replica r = new Replica(schema);
                    for (Op op : order) r.apply(op);
                    assertThat(r.snapshot()).isEqualTo(snapshot);
                }
            });
        });
    }

    @TestFactory
    Stream<DynamicTest> opHashVectors() {
        JsonObject file = Contract.read("vectors/op-hash/op-hash.json");
        return ((JsonArray) file.get("cases")).items().stream().map(c -> {
            JsonObject cs = (JsonObject) c;
            return DynamicTest.dynamicTest(Contract.str(cs.get("name")), () -> {
                Op op = Wire.decode(cs.get("op"));
                assertThat(Json.canonical(Wire.encode(op))).isEqualTo(Contract.str(cs.get("canonical")));
                assertThat(Json.canonical(cs.get("op"))).isEqualTo(Contract.str(cs.get("canonical")));
                assertThat(OpHash.of(op)).isEqualTo(Contract.str(cs.get("hash")));
            });
        });
    }
}
