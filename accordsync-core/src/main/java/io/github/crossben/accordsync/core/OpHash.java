package io.github.crossben.accordsync.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** The hash of an op, as the server stores it for compacted ops ({@code op_hash}). */
public final class OpHash {
    private OpHash() {}

    /**
     * SHA-256, lowercase hex, of the UTF-8 bytes of the canonical JSON of the op's wire form.
     * Canonical JSON escapes lone surrogates, so the UTF-8 is always valid.
     *
     * @param op the op
     * @return 64 lowercase hex digits
     */
    public static String of(Op op) {
        return sha256(Json.canonical(Wire.encode(op)));
    }

    static String sha256(String text) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : h) out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
