package io.github.crossben.accordsync.server;

/**
 * Log compaction settings (ADR-0005, ADR-0008).
 *
 * @param deviceTtlDays a device unseen this long is retired and no longer holds compaction back (30)
 * @param intervalMs how often a serving process compacts; 0 disables it (1 h; used by framework integrations)
 * @param minOps only records with at least this many ops are compacted (20)
 */
public record Compaction(double deviceTtlDays, long intervalMs, int minOps) {
    /** The defaults. */
    public static final Compaction DEFAULT = new Compaction(30, 3_600_000, 20);
}
