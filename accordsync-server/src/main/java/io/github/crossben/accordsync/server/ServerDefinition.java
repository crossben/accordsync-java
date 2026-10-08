package io.github.crossben.accordsync.server;

import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.Schema;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * An Accord server's definition: schema, scopes, access, auth and limits ({@code defineServer} in
 * TypeScript). Built with {@link AccordServer#define}; checked when built.
 *
 * @param schema the schema
 * @param scopes for each record type, the scope keys of a record from its current state
 * @param access the keys a user reads and writes, from their verified JWT claims
 * @param auth how tokens are verified
 * @param cors browser origins allowed to call the sync API (empty: CORS off)
 * @param rateLimitPerDevice per-device bucket, or null when rate limiting is off
 * @param rateLimitPerUser per-user bucket, or null when rate limiting is off
 * @param compaction compaction settings
 * @param limits request limits
 */
public record ServerDefinition(
        Schema schema,
        Map<String, Function<ScopedRecord, ? extends Collection<String>>> scopes,
        Function<JsonObject, Access> access,
        Auth auth,
        List<String> cors,
        RateLimit rateLimitPerDevice,
        RateLimit rateLimitPerUser,
        Compaction compaction,
        Limits limits) {

    /**
     * Validates and creates the definition.
     *
     * @param schema the schema
     * @param scopes the scope functions
     * @param access the access function
     * @param auth the auth config
     * @param cors the CORS origins
     * @param rateLimitPerDevice per device, or null
     * @param rateLimitPerUser per user, or null
     * @param compaction compaction
     * @param limits limits
     */
    public ServerDefinition {
        if (schema == null) throw new IllegalArgumentException("define: schema is required");
        scopes = Map.copyOf(scopes == null ? Map.of() : scopes);
        for (String type : schema.types().keySet()) {
            if (scopes.get(type) == null) {
                throw new IllegalArgumentException("define: no scope function for record type \"" + type + "\"");
            }
        }
        if (access == null) throw new IllegalArgumentException("define: access must be a function of the claims");
        if (auth == null || (auth.jwksUrl() == null) == (auth.hs256Secret() == null)) {
            throw new IllegalArgumentException("define: auth needs jwksUrl or hs256Secret (exactly one)");
        }
        if (auth.jwksCacheSeconds() < 0) throw new IllegalArgumentException("define: auth.jwksCacheSeconds must be >= 0");
        cors = List.copyOf(cors == null ? List.of() : cors);
        if ((rateLimitPerDevice == null) != (rateLimitPerUser == null)) {
            throw new IllegalArgumentException("define: rate limits are set per device and per user together");
        }
        checkRate("perDevice", rateLimitPerDevice);
        checkRate("perUser", rateLimitPerUser);
        if (limits == null) limits = Limits.DEFAULT;
        positive("maxBodyBytes", limits.maxBodyBytes(), 1);
        positive("maxConcurrentPushes", limits.maxConcurrentPushes(), 1);
        positive("maxScopeDelta", limits.maxScopeDelta(), 0);
        positive("maxPushOps", limits.maxPushOps(), 1);
        positive("maxPullLimit", limits.maxPullLimit(), 1);
        positive("maxSkewMs", limits.maxSkewMs(), 1);
        if (compaction == null) compaction = Compaction.DEFAULT;
        if (!(compaction.deviceTtlDays() > 0) || Double.isInfinite(compaction.deviceTtlDays())) {
            throw new IllegalArgumentException("define: compaction.deviceTtlDays must be > 0");
        }
        if (compaction.intervalMs() < 0) throw new IllegalArgumentException("define: compaction.intervalMs must be >= 0");
        if (compaction.minOps() < 1) throw new IllegalArgumentException("define: compaction.minOps must be an integer >= 1");
    }

    private static void checkRate(String name, RateLimit r) {
        if (r == null) return;
        if (!(r.perMinute() > 0) || Double.isInfinite(r.perMinute())) {
            throw new IllegalArgumentException("define: rateLimit." + name + ".perMinute must be > 0");
        }
        if (!(r.burst() > 0) || Double.isInfinite(r.burst())) {
            throw new IllegalArgumentException("define: rateLimit." + name + ".burst must be > 0");
        }
    }

    private static void positive(String name, long v, long min) {
        if (v < min) throw new IllegalArgumentException("define: limits." + name + " must be a positive integer");
    }

    /** The device-ttl in milliseconds. */
    double deviceTtlMs() {
        return compaction.deviceTtlDays() * 24 * 3_600_000;
    }

    /** Builds a {@link ServerDefinition}; see {@link AccordServer#define}. */
    public static final class Builder {
        private Schema schema;
        private final Map<String, Function<ScopedRecord, ? extends Collection<String>>> scopes = new LinkedHashMap<>();
        private Function<JsonObject, Access> access;
        private Auth auth;
        private List<String> cors = List.of();
        private RateLimit perDevice = RateLimit.perMinute(600);
        private RateLimit perUser = RateLimit.perMinute(1800);
        private Compaction compaction = Compaction.DEFAULT;
        private Limits limits = Limits.DEFAULT;

        Builder() {}

        /** @param s the schema @return this */
        public Builder schema(Schema s) {
            this.schema = s;
            return this;
        }

        /**
         * The scope function of a record type.
         *
         * @param type the record type
         * @param fn its scope keys from the record's current state
         * @return this
         */
        public Builder scope(String type, Function<ScopedRecord, ? extends Collection<String>> fn) {
            scopes.put(Objects.requireNonNull(type, "type"), fn);
            return this;
        }

        /** @param fn the keys a user reads and writes, from the verified claims @return this */
        public Builder access(Function<JsonObject, Access> fn) {
            this.access = fn;
            return this;
        }

        /** @param a how tokens are verified @return this */
        public Builder auth(Auth a) {
            this.auth = a;
            return this;
        }

        /** @param origins browser origins allowed to call the sync API @return this */
        public Builder cors(List<String> origins) {
            this.cors = origins;
            return this;
        }

        /**
         * Rate limits (defaults: 600/min per device, 1800/min per user).
         *
         * @param device per device
         * @param user per user
         * @return this
         */
        public Builder rateLimit(RateLimit device, RateLimit user) {
            this.perDevice = Objects.requireNonNull(device, "device");
            this.perUser = Objects.requireNonNull(user, "user");
            return this;
        }

        /** Turns rate limiting off (e.g. behind a proxy that limits already). @return this */
        public Builder noRateLimit() {
            this.perDevice = null;
            this.perUser = null;
            return this;
        }

        /** @param c compaction settings @return this */
        public Builder compaction(Compaction c) {
            this.compaction = c;
            return this;
        }

        /** @param l request limits @return this */
        public Builder limits(Limits l) {
            this.limits = l;
            return this;
        }

        /** @return the checked definition */
        public ServerDefinition build() {
            return new ServerDefinition(schema, scopes, access, auth, cors, perDevice, perUser, compaction, limits);
        }
    }
}
