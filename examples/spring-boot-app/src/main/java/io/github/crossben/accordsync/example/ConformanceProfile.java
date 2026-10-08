package io.github.crossben.accordsync.example;

import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonArray;
import io.github.crossben.accordsync.core.JsonNumber;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;
import io.github.crossben.accordsync.core.Schema;
import io.github.crossben.accordsync.server.Access;
import io.github.crossben.accordsync.server.AccordServer;
import io.github.crossben.accordsync.server.Auth;
import io.github.crossben.accordsync.server.Compaction;
import io.github.crossben.accordsync.server.Limits;
import io.github.crossben.accordsync.server.RateLimit;
import io.github.crossben.accordsync.server.ServerDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The conformance profile (app/conformance/PROFILE.md) as a {@link ServerDefinition}. */
public record ConformanceProfile(JsonObject json) {
    /**
     * Reads profile.json.
     *
     * @param file the file
     * @return the profile
     * @throws IOException when it cannot be read
     */
    public static ConformanceProfile load(Path file) throws IOException {
        return new ConformanceProfile((JsonObject) Json.parse(Files.readString(file)));
    }

    /** @return the HS256 secret */
    public String secret() {
        return ((JsonString) obj(json, "auth").get("hs256Secret")).value();
    }

    /** @return the issuer, or null */
    public String issuer() {
        return obj(json, "auth").get("issuer") instanceof JsonString s ? s.value() : null;
    }

    private static JsonObject obj(JsonObject o, String key) {
        return (JsonObject) o.get(key);
    }

    private static double num(JsonObject o, String key) {
        return ((JsonNumber) o.get(key)).doubleValue();
    }

    private static List<String> strings(JsonValue v) {
        List<String> out = new ArrayList<>();
        if (v instanceof JsonArray a) for (JsonValue x : a.items()) if (x instanceof JsonString s) out.add(s.value());
        return out;
    }

    private static RateLimit bucket(JsonObject o) {
        double perMinute = num(o, "perMinute");
        return o.get("burst") instanceof JsonNumber b ? RateLimit.of(perMinute, b.doubleValue()) : RateLimit.perMinute(perMinute);
    }

    /** @return the definition */
    public ServerDefinition definition() {
        JsonObject limits = obj(json, "limits");
        JsonObject rate = obj(json, "rateLimit");
        JsonObject comp = obj(json, "compaction");
        Map<String, Map<String, String>> types = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> t : obj(json, "schema").members().entrySet()) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (Map.Entry<String, JsonValue> f : ((JsonObject) t.getValue()).members().entrySet()) {
                fields.put(f.getKey(), ((JsonString) f.getValue()).value());
            }
            types.put(t.getKey(), fields);
        }
        Auth hs = Auth.hs256(secret());
        Auth auth = issuer() != null ? hs.issuer(issuer()) : hs;
        return AccordServer.define(d -> d
                .schema(Schema.define(types))
                .scope("dossier", r -> {
                    List<String> keys = new ArrayList<>();
                    if (r.string("agent") != null) keys.add("agent:" + r.string("agent"));
                    if (r.string("zone") != null) keys.add("zone:" + r.string("zone"));
                    return keys;
                })
                .access(claims -> {
                    List<String> write = new ArrayList<>();
                    write.add("agent:" + ((JsonString) claims.get("sub")).value());
                    for (String z : strings(claims.get("zones"))) write.add("zone:" + z);
                    List<String> read = new ArrayList<>(write);
                    for (String z : strings(claims.get("readonly_zones"))) read.add("zone:" + z);
                    return Access.of(read, write);
                })
                .auth(auth)
                .limits(Limits.DEFAULT
                        .withMaxPushOps((int) num(limits, "maxPushOps"))
                        .withMaxPullLimit((int) num(limits, "maxPullLimit"))
                        .withMaxScopeDelta((int) num(limits, "maxScopeDelta"))
                        .withMaxBodyBytes((int) num(limits, "maxBodyBytes"))
                        .withMaxSkewMs((long) num(limits, "maxSkewMs")))
                .rateLimit(bucket(obj(rate, "perDevice")), bucket(obj(rate, "perUser")))
                .compaction(new Compaction(num(comp, "deviceTtlDays"), (long) num(comp, "intervalMs"),
                        (int) num(comp, "minOps"))));
    }
}
