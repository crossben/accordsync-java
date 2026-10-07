package io.github.crossben.accordsync.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccordAutoConfigurationTest {
    @Test
    void speaksProtocolVersion1() {
        assertThat(AccordAutoConfiguration.PROTOCOL_VERSION).isEqualTo(1);
    }
}
