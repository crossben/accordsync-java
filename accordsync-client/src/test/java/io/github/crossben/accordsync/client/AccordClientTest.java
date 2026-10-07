package io.github.crossben.accordsync.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccordClientTest {
    @Test
    void speaksProtocolVersion1() {
        assertThat(AccordClient.PROTOCOL_VERSION).isEqualTo(1);
    }
}
