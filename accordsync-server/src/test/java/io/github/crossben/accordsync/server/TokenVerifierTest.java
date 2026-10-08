package io.github.crossben.accordsync.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.crossben.accordsync.core.JsonArray;
import io.github.crossben.accordsync.core.JsonString;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TokenVerifierTest {
    static final String SECRET = "accord-conformance-secret-at-least-32-bytes";

    static String sign(JWSSigner signer, JWSHeader header, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(signer);
        return jwt.serialize();
    }

    static String hs(JWTClaimsSet claims) throws Exception {
        return sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)), new JWSHeader(JWSAlgorithm.HS256), claims);
    }

    static Date in(long seconds) {
        return new Date(System.currentTimeMillis() + seconds * 1000);
    }

    @Test
    void hs256WithIssuer() throws Exception {
        TokenVerifier v = new TokenVerifier(Auth.hs256(SECRET).issuer("iss-1"));
        String ok = hs(new JWTClaimsSet.Builder().subject("ana").issuer("iss-1").expirationTime(in(60))
                .claim("zones", List.of("north")).build());
        var claims = v.verify("Bearer " + ok);
        assertThat(((JsonString) claims.get("sub")).value()).isEqualTo("ana");
        assertThat(((JsonArray) claims.get("zones")).items()).containsExactly(new JsonString("north"));

        assertThatThrownBy(() -> v.verify(null)).hasMessage("missing bearer token");
        assertThatThrownBy(() -> v.verify("Basic abc")).hasMessage("missing bearer token");
        String otherIssuer = hs(new JWTClaimsSet.Builder().subject("ana").issuer("evil").build());
        assertThatThrownBy(() -> v.verify("Bearer " + otherIssuer)).hasMessageStartingWith("invalid token");
        String expired = hs(new JWTClaimsSet.Builder().subject("ana").issuer("iss-1").expirationTime(in(-10)).build());
        assertThatThrownBy(() -> v.verify("Bearer " + expired)).hasMessageStartingWith("invalid token");
        String noSub = hs(new JWTClaimsSet.Builder().issuer("iss-1").build());
        assertThatThrownBy(() -> v.verify("Bearer " + noSub)).hasMessage("token has no \"sub\" claim");
        String wrongKey = sign(new MACSigner("another-secret-another-secret-another".getBytes(StandardCharsets.UTF_8)),
                new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().subject("ana").issuer("iss-1").build());
        assertThatThrownBy(() -> v.verify("Bearer " + wrongKey)).hasMessageStartingWith("invalid token");
        assertThatThrownBy(() -> v.verify("Bearer not.a.jwt")).isInstanceOf(AccordHttpException.class)
                .satisfies(e -> assertThat(((AccordHttpException) e).status()).isEqualTo(401));
    }

    @Test
    void audienceOnlyWhenConfigured() throws Exception {
        String noAud = hs(new JWTClaimsSet.Builder().subject("ana").build());
        String aud = hs(new JWTClaimsSet.Builder().subject("ana").audience("app").build());
        new TokenVerifier(Auth.hs256(SECRET)).verify("Bearer " + aud);
        TokenVerifier v = new TokenVerifier(Auth.hs256(SECRET).audience("app"));
        v.verify("Bearer " + aud);
        assertThatThrownBy(() -> v.verify("Bearer " + noAud)).hasMessageStartingWith("invalid token");
    }

    @Test
    void jwksIsFetchedOnceAndCached() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        RSAKey other = new RSAKeyGenerator(2048).keyID("k1").generate();
        AtomicInteger fetches = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        http.createContext("/jwks.json", ex -> {
            fetches.incrementAndGet();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, jwks.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(jwks);
            }
        });
        http.start();
        try {
            String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/jwks.json";
            TokenVerifier v = new TokenVerifier(Auth.jwks(url).issuer("idp"));
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build();
            JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("ana").issuer("idp").expirationTime(in(60)).build();
            String good = sign(new RSASSASigner(key), header, claims);
            assertThat(((JsonString) v.verify("Bearer " + good).get("sub")).value()).isEqualTo("ana");
            assertThat(((JsonString) v.verify("Bearer " + good).get("sub")).value()).isEqualTo("ana");
            assertThat(fetches.get()).isEqualTo(1);
            String forged = sign(new RSASSASigner(other), header, claims);
            assertThatThrownBy(() -> v.verify("Bearer " + forged)).hasMessageStartingWith("invalid token");
            // A JWKS never accepts a symmetric token.
            assertThatThrownBy(() -> v.verify("Bearer " + hs(claims))).hasMessageStartingWith("invalid token");
        } finally {
            http.stop(0);
        }
    }
}
