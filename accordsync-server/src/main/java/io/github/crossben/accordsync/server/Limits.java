package io.github.crossben.accordsync.server;

/**
 * Request limits (defaults of the TypeScript server).
 *
 * @param maxBodyBytes request body size; larger requests get 413 (5 MiB)
 * @param maxConcurrentPushes push transactions at once per server (8); keep it below the pool size
 * @param maxScopeDelta records sent directly when read scopes change; above, a full resync (2000)
 * @param maxPushOps ops per push request (500)
 * @param maxPullLimit items per pull page (1000)
 * @param maxSkewMs ops whose clock is further ahead are refused (24 h)
 */
public record Limits(int maxBodyBytes, int maxConcurrentPushes, int maxScopeDelta, int maxPushOps, int maxPullLimit,
        long maxSkewMs) {
    /** The defaults. */
    public static final Limits DEFAULT = new Limits(5 * 1024 * 1024, 8, 2000, 500, 1000, 24L * 3_600_000);

    /** @param v the value @return a copy with it */
    public Limits withMaxBodyBytes(int v) {
        return new Limits(v, maxConcurrentPushes, maxScopeDelta, maxPushOps, maxPullLimit, maxSkewMs);
    }

    /** @param v the value @return a copy with it */
    public Limits withMaxConcurrentPushes(int v) {
        return new Limits(maxBodyBytes, v, maxScopeDelta, maxPushOps, maxPullLimit, maxSkewMs);
    }

    /** @param v the value @return a copy with it */
    public Limits withMaxScopeDelta(int v) {
        return new Limits(maxBodyBytes, maxConcurrentPushes, v, maxPushOps, maxPullLimit, maxSkewMs);
    }

    /** @param v the value @return a copy with it */
    public Limits withMaxPushOps(int v) {
        return new Limits(maxBodyBytes, maxConcurrentPushes, maxScopeDelta, v, maxPullLimit, maxSkewMs);
    }

    /** @param v the value @return a copy with it */
    public Limits withMaxPullLimit(int v) {
        return new Limits(maxBodyBytes, maxConcurrentPushes, maxScopeDelta, maxPushOps, v, maxSkewMs);
    }

    /** @param v the value @return a copy with it */
    public Limits withMaxSkewMs(long v) {
        return new Limits(maxBodyBytes, maxConcurrentPushes, maxScopeDelta, maxPushOps, maxPullLimit, v);
    }
}
