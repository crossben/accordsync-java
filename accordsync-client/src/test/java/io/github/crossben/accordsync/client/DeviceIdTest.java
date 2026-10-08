package io.github.crossben.accordsync.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.crossben.accordsync.core.Hlc;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.Schema;
import io.github.crossben.accordsync.core.Strategy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeviceIdTest {
    @Test
    void deviceIdsAre32RandomHexDigitsBehindALetter() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) ids.add(AccordClient.randomDeviceId());
        assertThat(ids).hasSize(100);
        for (String id : ids) {
            assertThat(id).matches("d[0-9a-f]{32}");
            Hlc.assertNode(id);
        }
    }

    @Test
    void deviceIdsUseEveryByteOfTheSource() {
        Random fixed = new Random() {
            private static final long serialVersionUID = 1L;

            @Override
            public void nextBytes(byte[] bytes) {
                for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) 0xab;
            }
        };
        assertThat(AccordClient.randomDeviceId(fixed)).isEqualTo("d" + "ab".repeat(16));
    }

    @Test
    void clientGeneratesADeviceIdWhenNoneIsGiven() {
        MemoryStorage storage = new MemoryStorage();
        AccordClient c = AccordClient.open(AccordClient.options()
                .schema(Schema.define(Map.of("t", Map.of("f", Strategy.lww())))).storage(storage)
                .transport(new Transport() {
                    @Override
                    public PushResult push(String d, List<JsonObject> ops) {
                        throw new AssertionError();
                    }

                    @Override
                    public PullResult pull(String d, long cursor, int limit) {
                        throw new AssertionError();
                    }
                }));
        assertThat(c.deviceId()).matches("d[0-9a-f]{32}");
        assertThat(storage.load().meta().orElseThrow().deviceId()).isEqualTo(c.deviceId());
    }
}
