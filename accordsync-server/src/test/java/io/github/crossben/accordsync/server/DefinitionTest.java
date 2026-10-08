package io.github.crossben.accordsync.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.crossben.accordsync.core.Schema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefinitionTest {
    static final Schema SCHEMA = Schema.define(Map.of("dossier", Map.of("agent", "lww"), "note", Map.of("text", "lww")));

    static ServerDefinition.Builder valid(ServerDefinition.Builder d) {
        return d.schema(SCHEMA)
                .scope("dossier", r -> List.of("a"))
                .scope("note", r -> List.of("a"))
                .access(c -> Access.readWrite("a"))
                .auth(Auth.hs256("0123456789abcdef0123456789abcdef"));
    }

    @Test
    void acceptsAValidDefinitionWithDefaults() {
        ServerDefinition def = AccordServer.define(DefinitionTest::valid);
        assertThat(def.limits()).isEqualTo(Limits.DEFAULT);
        assertThat(def.compaction()).isEqualTo(Compaction.DEFAULT);
        assertThat(def.rateLimitPerDevice()).isEqualTo(RateLimit.perMinute(600));
        assertThat(def.rateLimitPerUser()).isEqualTo(RateLimit.perMinute(1800));
        assertThat(AccordServer.define(d -> valid(d).noRateLimit()).rateLimitPerDevice()).isNull();
    }

    @Test
    void everyRecordTypeNeedsAScopeFunction() {
        assertThatThrownBy(() -> AccordServer.define(d -> d.schema(SCHEMA).scope("dossier", r -> List.of())
                        .access(c -> Access.readWrite()).auth(Auth.hs256("x"))))
                .hasMessage("define: no scope function for record type \"note\"");
    }

    @Test
    void refusesInvalidSettings() {
        assertThatThrownBy(() -> AccordServer.define(d -> valid(d).auth(null))).hasMessageContaining("auth needs");
        assertThatThrownBy(() -> AccordServer.define(d -> valid(d).auth(new Auth("https://x", "s", null, null, 300))))
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> AccordServer.define(d -> valid(d).access(null))).hasMessageContaining("access");
        assertThatThrownBy(() -> AccordServer.define(d -> valid(d).schema(null))).hasMessageContaining("schema");
        assertThatThrownBy(() -> AccordServer.define(d -> valid(d).limits(Limits.DEFAULT.withMaxPushOps(0))))
                .hasMessage("define: limits.maxPushOps must be a positive integer");
        assertThatThrownBy(() -> AccordServer.define(d -> valid(d).limits(Limits.DEFAULT.withMaxScopeDelta(-1))))
                .hasMessageContaining("maxScopeDelta");
        assertThat(AccordServer.define(d -> valid(d).limits(Limits.DEFAULT.withMaxScopeDelta(0))).limits().maxScopeDelta()).isZero();
        assertThatThrownBy(() -> AccordServer.define(d -> valid(d).rateLimit(RateLimit.perMinute(0), RateLimit.perMinute(1))))
                .hasMessageContaining("rateLimit.perDevice.perMinute");
        assertThatThrownBy(() -> AccordServer.define(d -> valid(d).rateLimit(RateLimit.perMinute(1), RateLimit.of(1, 0))))
                .hasMessageContaining("rateLimit.perUser.burst");
        assertThatThrownBy(() -> AccordServer.define(d -> valid(d).compaction(new Compaction(0, 0, 1))))
                .hasMessageContaining("deviceTtlDays");
        assertThatThrownBy(() -> AccordServer.define(d -> valid(d).compaction(new Compaction(30, 0, 0))))
                .hasMessageContaining("minOps");
    }

    @Test
    void tokenBucketsRefillAndClear() {
        long[] now = {0};
        TokenBucketRateLimiter rl = new TokenBucketRateLimiter(RateLimit.of(60, 2), () -> now[0], 10);
        assertThat(rl.take("d")).isZero();
        assertThat(rl.take("d")).isZero();
        assertThat(rl.take("d")).isEqualTo(1000);
        now[0] = 1000;
        assertThat(rl.take("d")).isZero();
        assertThat(rl.take("d")).isEqualTo(1000);
        rl.clear();
        assertThat(rl.take("d")).isZero();
    }
}
