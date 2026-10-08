package io.github.crossben.accordsync.spring;

import io.github.crossben.accordsync.server.AccordServer;
import io.github.crossben.accordsync.server.HttpRequest;
import io.github.crossben.accordsync.server.HttpResponse;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** The "accord" Actuator health contributor: what the server's own {@code GET /health} answers. */
public class AccordHealthIndicator implements HealthIndicator {
    private final AccordServer server;

    /** @param server the server */
    public AccordHealthIndicator(AccordServer server) {
        this.server = server;
    }

    @Override
    public Health health() {
        HttpResponse res = server.handle(new HttpRequest("GET", "/health", "", null, null));
        Health.Builder b = res.status() == 200 ? Health.up() : Health.down();
        return b.withDetail("protocolVersion", AccordServer.PROTOCOL_VERSION).withDetail("health", res.bodyText()).build();
    }
}
