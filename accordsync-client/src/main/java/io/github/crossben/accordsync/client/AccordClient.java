package io.github.crossben.accordsync.client;

import io.github.crossben.accordsync.core.Protocol;

/** The Accord client: local-first writes and background sync (J2). */
public final class AccordClient {
    /** The protocol version this module speaks. */
    public static final int PROTOCOL_VERSION = Protocol.VERSION;

    private AccordClient() {}
}
