package io.github.crossben.accordsync.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WriterTest {
    static final Schema SCHEMA = Schema.define(Map.of("dossier", Map.of(
            "client_name", Strategy.lww(), "documents", Strategy.set(), "visits", Strategy.counter(), "status", Strategy.conflict())));

    static LocalWriter device(String id, long start) {
        long[] now = {start};
        return new LocalWriter(SCHEMA, id, () -> now[0]++);
    }

    static LocalWriter device(String id) {
        return device(id, 1_000);
    }

    static JsonValue field(LocalWriter w, String record, String field) {
        return w.replica().read(record).orElseThrow().get(field);
    }

    static JsonValue s(String v) {
        return new JsonString(v);
    }

    @Test
    void numbersOpsPerDeviceAndStampsIncreasingClocks() {
        LocalWriter a = device("a");
        AssignOp o1 = a.assign("dossier:1", "client_name", "Awa");
        AssignOp o2 = a.assign("dossier:1", "client_name", "Awa Diop");
        assertThat(List.of(o1.opId(), o2.opId())).containsExactly("a:1", "a:2");
        assertThat(Hlc.compare(o2.hlc(), o1.hlc())).isPositive();
        assertThat(field(a, "dossier:1", "client_name")).isEqualTo(s("Awa Diop"));
    }

    @Test
    void readsDefaultsForUntouchedFieldsAndLeavesNeverWrittenOnesOut() {
        LocalWriter a = device("a");
        a.inc("dossier:1", "visits", 2);
        assertThat(a.replica().read("dossier:1")).contains(new JsonObject(Map.of("documents", JsonArray.EMPTY, "visits", JsonNumber.of(2))));
        assertThat(a.replica().read("dossier:404")).isEmpty();
        assertThat(a.replica().snapshot()).isEqualTo("{\"dossier:1\":{\"documents\":[],\"visits\":2}}");
    }

    @Test
    void nullAssignIsAValueNotAbsence() {
        LocalWriter a = device("a");
        a.assign("dossier:1", "client_name", (Object) null);
        a.assign("dossier:1", "status", JsonNull.INSTANCE);
        assertThat(a.replica().snapshot())
                .isEqualTo("{\"dossier:1\":{\"client_name\":null,\"documents\":[],\"status\":{\"value\":null},\"visits\":0}}");
        assertThatThrownBy(() -> a.assign("dossier:1", "client_name", (JsonValue) null)).isInstanceOf(AccordException.class);
    }

    @Test
    void rejectsWritesThatDoNotMatchTheSchema() {
        LocalWriter a = device("a");
        assertThatThrownBy(() -> a.assign("dossier:1", "nope", 1)).hasMessageContaining("unknown field");
        assertThatThrownBy(() -> a.assign("ghost:1", "x", 1)).hasMessageContaining("unknown record type");
        assertThatThrownBy(() -> a.inc("dossier:1", "client_name", 1)).hasMessageContaining("lww");
        assertThatThrownBy(() -> a.inc("dossier:1", "visits", 0.5)).hasMessageContaining("integer");
        assertThatThrownBy(() -> a.inc("dossier:1", "visits", 9007199254740992L)).hasMessageContaining("integer");
        assertThatThrownBy(() -> a.add("dossier:1", "documents", Json.of(Map.of("a", 1)))).hasMessageContaining("string or number");
        assertThatThrownBy(() -> a.add("dossier:1", "documents", Double.NaN)).hasMessageContaining("string or number");
    }

    @Test
    void setsRemoveOnlyRemovesWhatTheWriterHasSeen() {
        LocalWriter a = device("a");
        LocalWriter b = device("b");
        b.receive(a.add("dossier:1", "documents", "cni.pdf"));
        RemoveOp remove = b.remove("dossier:1", "documents", "cni.pdf");
        AddOp readd = a.add("dossier:1", "documents", "cni.pdf");
        a.receive(remove);
        b.receive(readd);
        assertThat(field(a, "dossier:1", "documents")).isEqualTo(JsonArray.of(s("cni.pdf")));
        assertThat(field(b, "dossier:1", "documents")).isEqualTo(JsonArray.of(s("cni.pdf")));
    }

    @Test
    void aSeenRemoveDeletesTheElement() {
        LocalWriter a = device("a");
        LocalWriter b = device("b");
        b.receive(a.add("dossier:1", "documents", "x"));
        a.receive(b.remove("dossier:1", "documents", "x"));
        assertThat(field(a, "dossier:1", "documents")).isEqualTo(JsonArray.EMPTY);
        // The tombstone holds even if the add arrives after the remove.
        Replica r = new Replica(SCHEMA);
        List<Op> ops = a.replica().ops();
        r.apply(ops.get(1));
        r.apply(ops.get(0));
        assertThat(r.read("dossier:1").orElseThrow().get("documents")).isEqualTo(JsonArray.EMPTY);
    }

    @Test
    void oneAndOnePointZeroAreOneElementTheStringIsAnother() {
        LocalWriter a = device("a");
        a.add("dossier:1", "documents", JsonNumber.of(1));
        AddOp second = a.add("dossier:1", "documents", JsonNumber.of(1.0));
        assertThat(second.deps()).containsExactly("a:1");
        a.add("dossier:1", "documents", "1");
        LocalWriter b = device("b");
        b.receive(a.add("dossier:1", "documents", JsonNumber.of(-0.0)));
        assertThat(a.replica().snapshot()).isEqualTo("{\"dossier:1\":{\"documents\":[0,1,\"1\"],\"visits\":0}}");
        RemoveOp rm = a.remove("dossier:1", "documents", JsonNumber.of(1.0));
        assertThat(rm.deps()).containsExactly("a:2");
        assertThat(a.replica().snapshot()).isEqualTo("{\"dossier:1\":{\"documents\":[0,\"1\"],\"visits\":0}}");
        assertThat(a.remove("dossier:1", "documents", JsonNumber.of(0)).deps()).containsExactly("a:4");
    }

    @Test
    void conflictConcurrentAssignsSurfaceBothValues() {
        LocalWriter a = device("a");
        LocalWriter b = device("b");
        AssignOp x = a.assign("dossier:1", "status", "approved");
        AssignOp y = b.assign("dossier:1", "status", "rejected");
        a.receive(y);
        b.receive(x);
        for (Replica r : List.of(a.replica(), b.replica())) {
            assertThat(Json.canonical(r.read("dossier:1").orElseThrow().get("status")))
                    .isEqualTo("{\"conflicted\":[{\"opId\":\"a:1\",\"value\":\"approved\"},{\"opId\":\"b:1\",\"value\":\"rejected\"}]}");
            assertThat(r.conflicts()).containsExactly(new FieldRef("dossier:1", "status"));
        }
    }

    @Test
    void conflictASequentialEditReplacesTheValueItSaw() {
        LocalWriter a = device("a");
        LocalWriter b = device("b");
        b.receive(a.assign("dossier:1", "status", "draft"));
        a.receive(b.assign("dossier:1", "status", "submitted"));
        assertThat(Json.canonical(field(a, "dossier:1", "status"))).isEqualTo("{\"value\":\"submitted\"}");
        assertThat(a.replica().conflicts()).isEmpty();
    }

    @Test
    void conflictResolvingKeepsAnEditTheResolverHadNotSeen() {
        LocalWriter a = device("a");
        LocalWriter b = device("b");
        LocalWriter c = device("c");
        AssignOp x = a.assign("dossier:1", "status", "approved");
        AssignOp y = b.assign("dossier:1", "status", "rejected");
        AssignOp z = c.assign("dossier:1", "status", "on_hold");
        a.receive(y);
        AssignOp resolution = a.assign("dossier:1", "status", "approved");
        assertThat(resolution.deps()).containsExactly("a:1", "b:1");
        for (Op op : List.of(resolution, z, x)) b.receive(op);
        assertThat(Json.canonical(field(b, "dossier:1", "status")))
                .isEqualTo("{\"conflicted\":[{\"opId\":\"a:2\",\"value\":\"approved\"},{\"opId\":\"c:1\",\"value\":\"on_hold\"}]}");
    }

    @Test
    void countsEveryIncrementIncludingNegativeOnes() {
        LocalWriter a = device("a");
        LocalWriter b = device("b");
        IncOp p = a.inc("dossier:1", "visits", 3);
        IncOp q = b.inc("dossier:1", "visits", -1);
        a.receive(q);
        b.receive(p);
        assertThat(field(a, "dossier:1", "visits")).isEqualTo(JsonNumber.of(2));
        assertThat(field(b, "dossier:1", "visits")).isEqualTo(JsonNumber.of(2));
    }

    @Test
    void ignoresADuplicateOp() {
        LocalWriter a = device("a");
        LocalWriter b = device("b");
        IncOp op = a.inc("dossier:1", "visits", 5);
        assertThat(b.receive(op)).isEqualTo(ApplyResult.APPLIED);
        assertThat(b.receive(op)).isEqualTo(ApplyResult.DUPLICATE);
        assertThat(field(b, "dossier:1", "visits")).isEqualTo(JsonNumber.of(5));
    }

    @Test
    void lwwHighestClockWinsRegardlessOfArrivalOrder() {
        LocalWriter a = device("a", 1_000);
        LocalWriter b = device("b", 5_000);
        AssignOp x = b.assign("dossier:1", "client_name", "from b");
        AssignOp y = a.assign("dossier:1", "client_name", "from a");
        a.receive(x);
        b.receive(y);
        assertThat(field(a, "dossier:1", "client_name")).isEqualTo(s("from b"));
        assertThat(field(b, "dossier:1", "client_name")).isEqualTo(s("from b"));
    }

    @Test
    void refusesAnOpFromAClockTooFarAheadAndLeavesStateUntouched() {
        LocalWriter a = new LocalWriter(SCHEMA, "a", () -> 1_000, 60_000, null);
        LocalWriter liar = new LocalWriter(SCHEMA, "liar", () -> 10_000_000);
        Hlc before = a.clock();
        assertThatThrownBy(() -> a.receive(liar.inc("dossier:1", "visits", 1)))
                .isInstanceOf(ClockSkewException.class).hasMessageContaining("ahead");
        assertThat(a.replica().read("dossier:1")).isEmpty();
        assertThat(a.clock()).isEqualTo(before);
    }

    @Test
    void discardRollsBackARefusedOpAndKeepsTheRest() {
        LocalWriter a = device("a");
        IncOp keep = a.inc("dossier:1", "visits", 2);
        IncOp refused = a.inc("dossier:1", "visits", 40);
        a.assign("dossier:2", "status", "approved");
        a.discard(List.of(refused.opId()));
        assertThat(field(a, "dossier:1", "visits")).isEqualTo(JsonNumber.of(2));
        assertThat(a.replica().has(keep.opId())).isTrue();
        assertThat(a.replica().has(refused.opId())).isFalse();
        assertThat(Json.canonical(field(a, "dossier:2", "status"))).isEqualTo("{\"value\":\"approved\"}");
        assertThat(a.inc("dossier:1", "visits", 1).opId()).isEqualTo("a:4");
    }

    @Test
    void neverReusesAnOpIdAfterReceivingItsOwnOldOps() {
        LocalWriter before = device("a");
        List<IncOp> old = List.of(before.inc("dossier:1", "visits", 1), before.inc("dossier:1", "visits", 2));
        LocalWriter reinstalled = device("a");
        for (Op op : old) reinstalled.receive(op);
        assertThat(reinstalled.inc("dossier:1", "visits", 4).opId()).isEqualTo("a:3");
        assertThat(field(reinstalled, "dossier:1", "visits")).isEqualTo(JsonNumber.of(7));
        // A duplicate of an own op also advances (it may come back after a reset).
        LocalWriter again = device("a");
        again.replica().apply(old.get(1));
        again.receive(old.get(1));
        assertThat(again.inc("dossier:1", "visits", 1).opId()).isEqualTo("a:3");
    }

    @Test
    void advanceSeqTakesASequenceOrAnOwnOpIdAndNeverGoesBack() {
        LocalWriter a = device("a");
        a.advanceSeq(41);
        assertThat(a.inc("dossier:1", "visits", 1).opId()).isEqualTo("a:42");
        a.advanceSeq(3);
        a.advanceSeq("b:100");
        assertThat(a.seq()).isEqualTo(42);
        a.advanceSeq("a:99");
        assertThat(a.inc("dossier:1", "visits", 1).opId()).isEqualTo("a:100");
        assertThatThrownBy(() -> a.advanceSeq("garbage")).isInstanceOf(AccordException.class);
    }

    @Test
    void resumesFromAClockAndSequence() {
        Hlc last = new Hlc(50_000, 7, "a");
        LocalWriter a = new LocalWriter(SCHEMA, "a", () -> 1_000, LocalWriter.DEFAULT_MAX_SKEW_MS, new LocalWriter.Resume(last, 12));
        IncOp op = a.inc("dossier:1", "visits", 1);
        assertThat(op.opId()).isEqualTo("a:13");
        assertThat(op.hlc()).isEqualTo(new Hlc(50_000, 8, "a"));
    }

    @Test
    void aRejectedWriteConsumesNoSequenceNumber() {
        LocalWriter a = device("a");
        assertThatThrownBy(() -> a.assign("dossier:1", "nope", 1)).isInstanceOf(AccordException.class);
        assertThat(a.seq()).isZero();
        assertThat(a.clock()).isEqualTo(Hlc.initial("a"));
    }

    @Test
    void forgetDropsARecordButKeepsTheGivenOps() {
        LocalWriter a = device("a");
        a.inc("dossier:1", "visits", 1);
        IncOp pending = a.inc("dossier:1", "visits", 2);
        a.inc("dossier:2", "visits", 5);
        a.forget("dossier:1", Set.of(pending.opId()));
        assertThat(field(a, "dossier:1", "visits")).isEqualTo(JsonNumber.of(2));
        a.forget("dossier:1");
        assertThat(a.replica().records()).containsExactly("dossier:2");
        assertThat(a.inc("dossier:1", "visits", 1).opId()).isEqualTo("a:4");
    }

    @Test
    void forgetKeepsOtherRecordsBases() {
        LocalWriter a = device("a");
        a.inc("dossier:1", "visits", 3);
        a.inc("dossier:2", "visits", 4);
        RecordSnapshot snap = a.replica().snapshotRecord("dossier:2");
        Replica r = new Replica(SCHEMA);
        r.loadSnapshot(snap);
        r.apply(a.replica().ops().get(0));
        Replica next = r.forget("dossier:1", Set.of());
        assertThat(next.snapshot()).isEqualTo("{\"dossier:2\":{\"documents\":[],\"visits\":4}}");
        assertThat(next.bases()).containsExactly(snap);
    }

    @Test
    void loadSnapshotReappliesKeptOpsAndWithoutRestartsFromBases() {
        LocalWriter a = device("a");
        a.inc("dossier:1", "visits", 3);
        a.add("dossier:1", "documents", "x");
        a.assign("dossier:1", "client_name", "n");
        a.assign("dossier:1", "status", "s");
        RecordSnapshot snap = RecordSnapshot.fromJson(Json.parse(Json.canonical(a.replica().snapshotRecord("dossier:1").toJson())));
        IncOp local = a.inc("dossier:1", "visits", 10);
        Replica r = new Replica(SCHEMA);
        r.apply(local);
        r.loadSnapshot(snap, Set.of(local.opId()));
        assertThat(r.snapshot()).isEqualTo(a.replica().snapshot());
        assertThat(r.without(Set.of(local.opId())).read("dossier:1").orElseThrow().get("visits")).isEqualTo(JsonNumber.of(3));
        assertThat(r.observedDeps("dossier:1", "documents", s("x"))).containsExactly("a:2");
        assertThat(r.observedDeps("dossier:1", "status")).containsExactly("a:4");
    }

    @Test
    void setsReAddingAPresentElementReplacesTheTagsItsWriterSaw() {
        LocalWriter a = device("a");
        LocalWriter b = device("b");
        for (int i = 0; i < 50; i++) a.add("dossier:1", "documents", "cni.pdf");
        assertThat(a.replica().observedDeps("dossier:1", "documents", s("cni.pdf"))).hasSize(1);
        for (Op op : a.replica().ops()) b.receive(op);
        RemoveOp remove = b.remove("dossier:1", "documents", "cni.pdf");
        AddOp readd = a.add("dossier:1", "documents", "cni.pdf");
        a.receive(remove);
        b.receive(readd);
        assertThat(field(a, "dossier:1", "documents")).isEqualTo(JsonArray.of(s("cni.pdf")));
        assertThat(field(b, "dossier:1", "documents")).isEqualTo(JsonArray.of(s("cni.pdf")));
    }

    @Test
    void opsAndRecordsAreOrderedByCodeUnitNeverCaseFolded() {
        Schema schema = Schema.define(Map.of("t", Map.of("v", "counter")));
        Replica r = new Replica(schema);
        for (String d : List.of("a", "B", "_x", "Z")) {
            r.apply(new IncOp(d + ":1", "t:" + d, "v", new Hlc(1, 0, d), 1));
        }
        assertThat(r.ops()).extracting(Op::opId).containsExactly("B:1", "Z:1", "_x:1", "a:1");
        assertThat(r.records()).containsExactly("t:B", "t:Z", "t:_x", "t:a");
    }

    @Test
    void schemaRejectsBadDefinitions() {
        assertThatThrownBy(() -> Schema.define(Map.of("1x", Map.of("a", "lww")))).isInstanceOf(AccordException.class);
        assertThatThrownBy(() -> Schema.define(Map.of("x", Map.of("a", "max")))).hasMessageContaining("unknown strategy");
        assertThat(Schema.define(Map.of("x", Map.of("a", "set"))).strategyFor("x:1", "a")).isEqualTo(Strategy.SET);
    }
}
