package io.github.crossben.accordsync.client;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Where a device stands.
 *
 * @param pending local ops not yet acknowledged
 * @param cursor the pull cursor
 * @param lastSyncAt when the last full round ended (ms, from the client's clock)
 * @param lastError the error of the last failed background round, cleared by a good round
 */
public record SyncStatus(int pending, long cursor, OptionalLong lastSyncAt, Optional<Throwable> lastError) {}
