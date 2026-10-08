package io.github.crossben.accordsync.core;

/** What applying an op did. */
public enum ApplyResult {
    /** The op was new and is now part of the state. */
    APPLIED,
    /** The op was already applied; nothing changed. */
    DUPLICATE
}
