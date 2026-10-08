package io.github.crossben.accordsync.server;

/**
 * A token bucket: {@code burst} requests at once, refilled at {@code perMinute}.
 *
 * @param perMinute sustained requests per minute
 * @param burst bucket size (defaults to {@code perMinute})
 */
public record RateLimit(double perMinute, double burst) {
    /**
     * A bucket of {@code perMinute} requests.
     *
     * @param perMinute the rate
     * @return the limit
     */
    public static RateLimit perMinute(double perMinute) {
        return new RateLimit(perMinute, perMinute);
    }

    /**
     * A bucket with its own size.
     *
     * @param perMinute the rate
     * @param burst the bucket size
     * @return the limit
     */
    public static RateLimit of(double perMinute, double burst) {
        return new RateLimit(perMinute, burst);
    }
}
