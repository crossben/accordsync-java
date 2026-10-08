package io.github.crossben.accordsync.client;

/** Undoes a listener registration. */
@FunctionalInterface
public interface Subscription {
    /** Removes the listener; calling it again does nothing. */
    void unsubscribe();
}
