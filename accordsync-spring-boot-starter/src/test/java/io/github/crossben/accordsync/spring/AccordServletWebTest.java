package io.github.crossben.accordsync.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.crossben.accordsync.server.ServerDefinition;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

/** The starter in a real servlet container (Tomcat), against a stub definition and a database that is down. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"accord.migrate-on-startup=false", "accord.path-prefix=/sync", "spring.sql.init.mode=never"})
class AccordServletWebTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        ServerDefinition definition() {
            return Stubs.definition();
        }

        @Bean
        DataSource dataSource() {
            return new Stubs.DownDataSource();
        }
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder at(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    @Test
    void healthReportsTheDatabase() throws Exception {
        HttpResponse<String> res = send(at("/sync/health"));
        assertThat(res.statusCode()).isEqualTo(503);
        assertThat(res.body()).isEqualTo("{\"status\":\"unavailable\",\"reason\":\"database unreachable\"}");
        assertThat(res.headers().firstValue("Accord-Protocol")).hasValue("1");
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
    }

    @Test
    void pushWithoutATokenIs401() throws Exception {
        HttpResponse<String> res = send(at("/sync/v1/push").POST(HttpRequest.BodyPublishers.ofString("{\"ops\":[]}"))
                .header("Content-Type", "application/json"));
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).startsWith("{\"error\":");
        assertThat(res.headers().firstValue("Accord-Protocol")).hasValue("1");
    }

    @Test
    void aBodyOverTheLimitIs413BeforeAuth() throws Exception {
        HttpResponse<String> res = send(at("/sync/v1/push").POST(HttpRequest.BodyPublishers.ofString("x".repeat(5000))));
        assertThat(res.statusCode()).isEqualTo(413);
        assertThat(res.body()).isEqualTo("{\"error\":\"request body too large\"}");
    }

    @Test
    void unknownPathsUnderV1AreJson404() throws Exception {
        HttpResponse<String> res = send(at("/sync/v1/nope"));
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).isEqualTo("{\"error\":\"not found\"}");
    }

    @Test
    void corsComesFromTheDefinition() throws Exception {
        HttpResponse<String> pre = send(at("/sync/v1/push").method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://app.example").header("Access-Control-Request-Method", "POST"));
        assertThat(pre.statusCode()).isEqualTo(204);
        assertThat(pre.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://app.example");
        assertThat(pre.headers().firstValue("Access-Control-Max-Age")).hasValue("600");
        HttpResponse<String> other = send(at("/sync/health").header("Origin", "https://evil.example"));
        assertThat(other.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(other.headers().firstValue("Vary")).hasValue("Origin");
    }

    @Test
    void pathsOutsideThePrefixAreNotAccords() throws Exception {
        HttpResponse<String> res = send(at("/v1/pull"));
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.headers().firstValue("Accord-Protocol")).isEmpty();
    }
}
