package com.github.highcumontoa.gateway;

import com.github.highcumontoa.gateway.model.GatewayStatusSnapshot;
import com.github.highcumontoa.gateway.runtime.ReadingStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
class GatewayApplicationTests {
    @Autowired
    ReadingStore store;

    @Test
    void contextStartsAndConnectsToLocalDevice() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            GatewayStatusSnapshot status = store.status();
            assertFalse(store.snapshots().isEmpty());
        });
    }
}
