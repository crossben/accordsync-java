package io.github.crossben.accordsync.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** The request layer without a database: routing, headers, limits checked before auth. */
class HandleTest {
    /** A database that is never reachable. */
    static final DataSource DOWN = new DataSource() {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("down");
        }

        @Override
        public Connection getConnection(String u, String p) throws SQLException {
            throw new SQLException("down");
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int s) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> c) throws SQLException {
            throw new SQLException();
        }

        @Override
        public boolean isWrapperFor(Class<?> c) {
            return false;
        }
    };

    static AccordServer server(List<String> cors) {
        return new AccordServer(AccordServer.define(d -> DefinitionTest.valid(d).cors(cors)
                .limits(Limits.DEFAULT.withMaxBodyBytes(10))), DOWN);
    }

    static HttpResponse call(AccordServer s, String method, String path, Map<String, String> headers, String body) {
        return s.handle(new HttpRequest(method, path, "", headers, body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void unknownPathsGetJson404WithTheProtocolHeader() {
        HttpResponse r = call(server(List.of()), "GET", "/nope", Map.of(), "");
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.bodyText()).isEqualTo("{\"error\":\"not found\"}");
        assertThat(r.header("content-type")).isEqualTo("application/json");
        assertThat(r.header("Accord-Protocol")).isEqualTo("1");
        assertThat(call(server(List.of()), "GET", "/v1/push", Map.of(), "").status()).isEqualTo(404);
    }

    @Test
    void bodyLimitComesBeforeAuth() {
        HttpResponse r = call(server(List.of()), "POST", "/v1/push", Map.of(), "{\"ops\":[1,2,3,4]}");
        assertThat(r.status()).isEqualTo(413);
        assertThat(r.bodyText()).contains("request body too large");
        HttpResponse declared = call(server(List.of()), "POST", "/v1/push", Map.of("Content-Length", "99"), "");
        assertThat(declared.status()).isEqualTo(413);
        HttpResponse noAuth = call(server(List.of()), "POST", "/v1/push", Map.of(), "{}");
        assertThat(noAuth.status()).isEqualTo(401);
        assertThat(noAuth.header("Accord-Protocol")).isEqualTo("1");
    }

    @Test
    void healthReports503WhenTheDatabaseIsDown() {
        HttpResponse r = call(server(List.of()), "GET", "/health", Map.of(), "");
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.bodyText()).contains("database unreachable");
    }

    @Test
    void cors() {
        AccordServer s = server(List.of("https://app.example.com"));
        HttpResponse pre = call(s, "OPTIONS", "/v1/push", Map.of("Origin", "https://app.example.com"), "");
        assertThat(pre.status()).isEqualTo(204);
        assertThat(pre.header("Access-Control-Allow-Origin")).isEqualTo("https://app.example.com");
        assertThat(pre.header("Access-Control-Allow-Headers")).isEqualTo("Authorization,Accord-Device,Content-Type");
        assertThat(pre.header("Accord-Protocol")).isEqualTo("1");
        HttpResponse other = call(s, "GET", "/nope", Map.of("Origin", "https://evil.example"), "");
        assertThat(other.header("Access-Control-Allow-Origin")).isNull();
        assertThat(other.header("Access-Control-Expose-Headers")).isEqualTo("Accord-Protocol");
        assertThat(call(server(List.of()), "GET", "/nope", Map.of("Origin", "https://x"), "").header("Vary")).isNull();
    }

    @Test
    void queryParsing() {
        assertThat(AccordServer.query("cursor=0x10&limit=&cursor=5&x")).containsEntry("cursor", "0x10")
                .containsEntry("limit", "").containsEntry("x", "");
        assertThat(AccordServer.query("a=%20b+c&bad=%zz")).containsEntry("a", " b c").containsEntry("bad", "%zz");
    }
}
