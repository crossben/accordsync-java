package io.github.crossben.accordsync.server;

/**
 * How bearer tokens are verified: a JWKS URL (production) or an HS256 secret (development).
 *
 * @param jwksUrl the JWKS URL, or null
 * @param hs256Secret the shared secret, or null
 * @param issuer the required {@code iss}, or null
 * @param audience the required {@code aud}, or null
 * @param jwksCacheSeconds how long fetched keys are cached
 */
public record Auth(String jwksUrl, String hs256Secret, String issuer, String audience, long jwksCacheSeconds) {
    /**
     * Tokens signed by keys of a JWKS (RSA, RSA-PSS or EC), fetched once and cached for 5 minutes.
     *
     * @param url the JWKS URL
     * @return the config
     */
    public static Auth jwks(String url) {
        return new Auth(url, null, null, null, 300);
    }

    /**
     * Shared-secret HS256 tokens: for development and tests. Prefer JWKS in production.
     *
     * @param secret the secret (at least 32 bytes)
     * @return the config
     */
    public static Auth hs256(String secret) {
        return new Auth(null, secret, null, null, 300);
    }

    /**
     * Requires this issuer.
     *
     * @param iss the issuer
     * @return a copy
     */
    public Auth issuer(String iss) {
        return new Auth(jwksUrl, hs256Secret, iss, audience, jwksCacheSeconds);
    }

    /**
     * Requires this audience.
     *
     * @param aud the audience
     * @return a copy
     */
    public Auth audience(String aud) {
        return new Auth(jwksUrl, hs256Secret, issuer, aud, jwksCacheSeconds);
    }

    /**
     * Sets how long JWKS keys are cached.
     *
     * @param seconds the time to live
     * @return a copy
     */
    public Auth jwksCacheSeconds(long seconds) {
        return new Auth(jwksUrl, hs256Secret, issuer, audience, seconds);
    }
}
