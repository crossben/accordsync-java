package io.github.crossben.accordsync.server;

/**
 * A request limiter keyed by device or user. The default, {@link TokenBucketRateLimiter}, keeps its
 * buckets in memory per process; implement this over a shared store if that matters.
 */
public interface RateLimiter {
    /**
     * Takes one token for {@code key}.
     *
     * @param key a device id or a user
     * @return 0 if allowed, otherwise the milliseconds to wait
     */
    long take(String key);

    /** Forgets every bucket: all start full again. */
    void clear();
}
