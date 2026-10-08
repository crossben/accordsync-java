package io.github.crossben.accordsync.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpTransportTest {
    static final JsonObject OP = (JsonObject) Json.parse(
            "{\"op_id\":\"d:1\",\"record\":\"t:1\",\"field\":\"f\",\"kind\":\"inc\",\"by\":1,\"hlc\":\"1:00000:d\"}");

    record Seen(String method, String uri, Map<String, List<String>> headers, String body) {
        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue().get(0);
            return null;
        }
    }

    record Answer(int status, String body) {}

    HttpServer http;
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    final Deque<Answer> answers = new ArrayDeque<>();
    String url;

    @BeforeEach
    void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", this::handle);
        http.start();
        url = "http://127.0.0.1:" + http.getAddress().getPort() + "//";
    }

    @AfterEach
    void stop() {
        http.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().toString(), Map.copyOf(ex.getRequestHeaders()), body));
        Answer a;
        synchronized (answers) {
            a = answers.poll();
        }
        byte[] out = a.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(a.status(), out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    void answer(int status, String body) {
        synchronized (answers) {
            answers.add(new Answer(status, body));
        }
    }

    @Test
    void pushSendsAuthDeviceAndJson() {
        answer(200, "{\"acked\":[\"d:1\"],\"refused\":[{\"op_id\":\"d:2\",\"reason\":\"no\"}]}");
        PushResult res = new HttpTransport(url, () -> "tok").push("dev-1", List.of(OP));
        assertThat(res.acked()).containsExactly("d:1");
        assertThat(res.refused()).containsExactly(new PushResult.Refused("d:2", "no"));
        Seen req = seen.get(0);
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.uri()).isEqualTo("/v1/push");
        assertThat(req.header("Authorization")).isEqualTo("Bearer tok");
        assertThat(req.header("Accord-Device")).isEqualTo("dev-1");
        assertThat(req.header("Content-Type")).isEqualTo("application/json");
        assertThat(Json.parse(req.body())).isEqualTo(Json.of(Map.of("ops", List.of(OP))));
    }

    @Test
    void pushSendsUtf8AndLoneSurrogatesEscaped() {
        answer(200, "{\"acked\":[],\"refused\":[]}");
        JsonObject op = (JsonObject) Json.parse(
                "{\"op_id\":\"d:1\",\"record\":\"t:1\",\"field\":\"f\",\"kind\":\"assign\",\"value\":\"é\\ud800\",\"deps\":[],\"hlc\":\"1:00000:d\"}");
        new HttpTransport(url, () -> "tok").push("d", List.of(op));
        assertThat(seen.get(0).body()).contains("é\\ud800");
    }

    @Test
    void pullParsesItems() {
        answer(200, "{\"items\":[{\"type\":\"op\",\"op\":" + Json.stringify(OP) + "},"
                + "{\"type\":\"snapshot\",\"snapshot\":{\"record\":\"t:2\",\"fields\":{\"f\":{\"strategy\":\"counter\",\"total\":3}}}},"
                + "{\"type\":\"exit\",\"record\":\"t:3\"}],\"cursor\":12,\"has_more\":true,\"device_seq\":4}");
        PullResult r = new HttpTransport(url, () -> "tok").pull("dev-1", 5, 100);
        assertThat(r).isInstanceOf(PullResult.PullPage.class);
        PullResult.PullPage page = (PullResult.PullPage) r;
        assertThat(page.cursor()).isEqualTo(12);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.deviceSeq()).hasValue(4);
        assertThat(page.items().get(0)).isEqualTo(new PullItem.OpItem(OP));
        assertThat(((PullItem.SnapshotItem) page.items().get(1)).snapshot().record()).isEqualTo("t:2");
        assertThat(page.items().get(2)).isEqualTo(new PullItem.ExitItem("t:3"));
        Seen req = seen.get(0);
        assertThat(req.method()).isEqualTo("GET");
        assertThat(req.uri()).isEqualTo("/v1/pull?cursor=5&limit=100");
        assertThat(req.header("Authorization")).isEqualTo("Bearer tok");
        assertThat(req.header("Accord-Device")).isEqualTo("dev-1");
    }

    @Test
    void pullWithoutDeviceSeqAndResync() {
        answer(200, "{\"items\":[],\"cursor\":0,\"has_more\":false}");
        answer(200, "{\"resync_required\":true}");
        HttpTransport t = new HttpTransport(url, () -> "tok");
        PullResult first = t.pull("d", 0, 10);
        assertThat(((PullResult.PullPage) first).deviceSeq()).isEmpty();
        assertThat(t.pull("d", 0, 10)).isInstanceOf(PullResult.ResyncRequired.class);
    }

    @Test
    void errorStatusRaisesHttpException() {
        answer(401, "bad token");
        assertThatThrownBy(() -> new HttpTransport(url, () -> "tok").pull("d", 0, 10))
                .isInstanceOfSatisfying(HttpException.class, e -> {
                    assertThat(e.status()).isEqualTo(401);
                    assertThat(e.getMessage()).contains("bad token");
                });
    }

    @Test
    void malformedBodyIsAnError() {
        answer(200, "{\"items\":[{\"type\":\"what\"}],\"cursor\":0,\"has_more\":false}");
        assertThatThrownBy(() -> new HttpTransport(url, () -> "tok").pull("d", 0, 10)).hasMessageContaining("what");
    }

    @Test
    void tokenIsReadBeforeEveryRequest() {
        answer(200, "{\"items\":[],\"cursor\":0,\"has_more\":false}");
        answer(200, "{\"items\":[],\"cursor\":0,\"has_more\":false}");
        Iterator<String> tokens = List.of("a", "b").iterator();
        HttpTransport t = new HttpTransport(url, tokens::next);
        t.pull("d", 0, 1);
        t.pull("d", 0, 1);
        assertThat(seen).extracting(s -> s.header("Authorization")).containsExactly("Bearer a", "Bearer b");
    }

    @Test
    void clientSyncsThroughHttp() {
        answer(200, "{\"acked\":[\"dev:1\"],\"refused\":[]}");
        answer(200, "{\"items\":[{\"type\":\"op\",\"op\":{\"op_id\":\"other:1\",\"record\":\"t:1\",\"field\":\"n\",\"kind\":\"inc\",\"by\":2,\"hlc\":\"5:00000:other\"}}],\"cursor\":2,\"has_more\":false,\"device_seq\":1}");
        AccordClient c = AccordClient.open(AccordClient.options()
                .schema(io.github.crossben.accordsync.core.Schema.define(Map.of("t", Map.of("n", "counter"))))
                .storage(new MemoryStorage()).transport(new HttpTransport(url, () -> "tok")).deviceId("dev"));
        c.inc("t:1", "n", 1);
        c.sync();
        assertThat(c.read("t:1").orElseThrow().get("n")).isEqualTo(Json.of(3));
        assertThat(c.status().cursor()).isEqualTo(2);
    }
}
