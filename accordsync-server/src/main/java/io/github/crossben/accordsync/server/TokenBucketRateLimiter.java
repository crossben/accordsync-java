package io.github.crossben.accordsync.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/** Token buckets in memory ({@code ratelimit.ts}): each key gets {@code burst} at once, refilled at {@code perMinute}. */
public final class TokenBucketRateLimiter implements RateLimiter {
    private final double rate;
    private final double burst;
    private final LongSupplier now;
    private final int maxKeys;
    private final Map<String, double[]> buckets = new HashMap<>();

    /**
     * Creates the limiter.
     *
     * @param limit the bucket
     * @param now the clock in milliseconds
     * @param maxKeys buckets kept before refilled ones are evicted
     */
    public TokenBucketRateLimiter(RateLimit limit, LongSupplier now, int maxKeys) {
        if (!(limit.perMinute() > 0)) throw new IllegalArgumentException("rate limit perMinute must be > 0");
        this.rate = limit.perMinute() / 60_000;
        this.burst = limit.burst();
        this.now = now;
        this.maxKeys = maxKeys;
    }

    /**
     * Creates the limiter with the wall clock and 100 000 keys.
     *
     * @param limit the bucket
     */
    public TokenBucketRateLimiter(RateLimit limit) {
        this(limit, System::currentTimeMillis, 100_000);
    }

    @Override
    public synchronized long take(String key) {
        double t = now.getAsLong();
        double[] b = buckets.get(key);
        if (b == null) {
            if (buckets.size() >= maxKeys) evict(t);
            b = new double[] {burst, t};
            buckets.put(key, b);
        }
        b[0] = Math.min(burst, b[0] + (t - b[1]) * rate);
        b[1] = t;
        if (b[0] >= 1) {
            b[0] -= 1;
            return 0;
        }
        return (long) Math.ceil((1 - b[0]) / rate);
    }

    @Override
    public synchronized void clear() {
        buckets.clear();
    }

    private void evict(double t) {
        for (Iterator<double[]> it = buckets.values().iterator(); it.hasNext(); ) {
            double[] b = it.next();
            if (b[0] + (t - b[1]) * rate >= burst) it.remove();
        }
    }
}
