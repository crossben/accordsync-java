package io.github.crossben.accordsync.server;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.github.crossben.accordsync.core.AccordException;
import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies {@code Authorization: Bearer <jwt>} like the TypeScript server (jose): {@code exp} and
 * {@code nbf} checked when present (no clock tolerance), {@code iss} and {@code aud} only when
 * configured, {@code sub} required. With a JWKS, keys are fetched once and cached.
 */
public final class TokenVerifier {
    private static final Pattern BEARER = Pattern.compile("Bearer (.+)", Pattern.DOTALL);

    /** Asymmetric algorithms accepted with a JWKS (EdDSA needs Tink, which this module leaves out). */
    static final Set<JWSAlgorithm> ASYMMETRIC = Set.of(
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512);

    private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

    /**
     * Creates the verifier.
     *
     * @param auth the auth config
     */
    public TokenVerifier(Auth auth) {
        JWKSource<SecurityContext> keys;
        Set<JWSAlgorithm> algorithms;
        if (auth.jwksUrl() != null) {
            try {
                long ttl = auth.jwksCacheSeconds() * 1000;
                keys = JWKSourceBuilder.<SecurityContext>create(URI.create(auth.jwksUrl()).toURL())
                        .cache(Math.max(ttl, 1), 15_000)
                        .build();
            } catch (MalformedURLException | IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid jwksUrl: " + auth.jwksUrl(), e);
            }
            algorithms = ASYMMETRIC;
        } else {
            keys = new ImmutableSecret<>(auth.hs256Secret().getBytes(StandardCharsets.UTF_8));
            algorithms = Set.of(JWSAlgorithm.HS256);
        }
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithms, keys));
        processor.setJWSTypeVerifier((type, context) -> {});
        JWTClaimsSet exact = auth.issuer() != null ? new JWTClaimsSet.Builder().issuer(auth.issuer()).build() : null;
        DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(
                auth.audience() != null ? Set.of(auth.audience()) : null, exact, null, null);
        claims.setMaxClockSkew(0);
        processor.setJWTClaimsSetVerifier(claims);
    }

    /**
     * Verifies the header.
     *
     * @param authorization the {@code Authorization} header, or null
     * @return the claims, always with a non-empty string {@code sub}
     * @throws AccordHttpException 401 when not authenticated
     */
    public JsonObject verify(String authorization) {
        Matcher m = BEARER.matcher(authorization == null ? "" : authorization);
        if (!m.matches()) throw AccordHttpException.unauthorized("missing bearer token");
        JsonValue payload;
        try {
            SignedJWT jwt = SignedJWT.parse(m.group(1));
            processor.process(jwt, null);
            payload = Json.parse(jwt.getPayload().toString());
        } catch (ParseException | BadJOSEException | JOSEException | AccordException | IllegalStateException e) {
            throw AccordHttpException.unauthorized("invalid token: " + e.getMessage());
        }
        if (!(payload instanceof JsonObject o) || !(o.get("sub") instanceof JsonString sub) || sub.value().isEmpty()) {
            throw AccordHttpException.unauthorized("token has no \"sub\" claim");
        }
        return o;
    }
}
