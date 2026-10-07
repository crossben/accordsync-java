package io.github.crossben.accordsync.server;

import io.github.crossben.accordsync.core.Protocol;

/** The Accord sync server on PostgreSQL (J4). */
public final class AccordServer {
    /** The protocol version this module speaks. */
    public static final int PROTOCOL_VERSION = Protocol.VERSION;

    private AccordServer() {}
}
