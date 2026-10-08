package io.github.crossben.accordsync.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.crossben.accordsync.server.ServerDefinition;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The profile builds a valid definition (the running app is tested by the conformance suite in CI). */
class ExampleApplicationTest {
    @Test
    void theConformanceProfileBuildsADefinition() throws Exception {
        ConformanceProfile p = ConformanceProfile.load(Path.of("../../contract/conformance/profile.json"));
        ServerDefinition d = p.definition();
        assertThat(d.limits().maxPushOps()).isEqualTo(20);
        assertThat(d.limits().maxBodyBytes()).isEqualTo(16384);
        assertThat(d.compaction().intervalMs()).isZero();
        assertThat(p.issuer()).isEqualTo("accord-conformance");
    }
}
