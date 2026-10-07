package io.github.crossben.accordsync.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The shared contract (golden vectors, protocol schemas), committed in contract/. J1 makes the core pass it. */
class ContractTest {
    static Path contract() {
        return Path.of(System.getProperty("accord.contract", "../contract"));
    }

    @Test
    void speaksProtocolVersion1() {
        assertThat(Protocol.VERSION).isEqualTo(1);
    }

    @Test
    void goldenVectorsArePresent() throws IOException {
        try (Stream<Path> files = Files.list(contract().resolve("vectors"))) {
            List<String> names = files.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json")).sorted().toList();
            assertThat(names).containsExactly("conflict.json", "counter.json", "lww.json", "set.json");
        }
        assertThat(contract().resolve("vectors/random/cases.json")).exists();
        assertThat(contract().resolve("vectors/op-hash/op-hash.json")).exists();
        assertThat(contract().resolve("conformance/profile.json")).exists();
    }

    @Test
    void protocolSchemasArePresent() {
        for (String name : List.of("WireOp", "PushRequest", "PushResponse", "PullItem", "PullResponse")) {
            assertThat(contract().resolve("protocol/v1/" + name + ".schema.json")).exists();
        }
    }
}
