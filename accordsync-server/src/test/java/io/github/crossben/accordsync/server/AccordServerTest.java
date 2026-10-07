package io.github.crossben.accordsync.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccordServerTest {
    @Test
    void speaksProtocolVersion1() {
        assertThat(AccordServer.PROTOCOL_VERSION).isEqualTo(1);
    }
}
