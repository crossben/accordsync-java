package io.github.crossben.accordsync.spring;

import io.github.crossben.accordsync.core.Protocol;

/** Spring Boot auto-configuration for the Accord server (J5). */
public final class AccordAutoConfiguration {
    /** The protocol version this module speaks. */
    public static final int PROTOCOL_VERSION = Protocol.VERSION;

    private AccordAutoConfiguration() {}
}
