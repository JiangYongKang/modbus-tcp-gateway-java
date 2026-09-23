package com.github.highcumontoa.gateway.web;

import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.model.GatewayStatusSnapshot;
import com.github.highcumontoa.gateway.model.PointQuality;
import com.github.highcumontoa.gateway.model.ReadingSnapshot;
import com.github.highcumontoa.gateway.runtime.ReadingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReadingControllerTest {
    private MockMvc mockMvc;
    private StubReadingStore store;

    @BeforeEach
    void setUp() {
        store = new StubReadingStore();
        mockMvc = MockMvcBuilders.standaloneSetup(new ReadingController(store)).build();
    }

    @Test
    void exposesReadingsAndStatusThroughHttpApi() throws Exception {
        ReadingSnapshot reading = new ReadingSnapshot("temperature-c", 123.4d, 123.4d,
                PointQuality.GOOD, null, "OK", "00010000000501030204D2", 1L,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                1L, 1L, 0L);
        GatewayStatusSnapshot status = new GatewayStatusSnapshot("CONNECTED", "127.0.0.1:15020",
                1L, 1L, 1L, 0L, 0L, 0L, 0L, 0L,
                Instant.parse("2026-01-01T00:00:00Z"), null, "", Map.of());
        store.readings = List.of(reading);
        store.byName.put("temperature-c", reading);
        store.status = status;

        mockMvc.perform(get("/api/readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pointName").value("temperature-c"))
                .andExpect(jsonPath("$[0].quality").value("GOOD"));

        mockMvc.perform(get("/api/readings/temperature-c"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pointName").value("temperature-c"))
                .andExpect(jsonPath("$.value").value(123.4d))
                .andExpect(jsonPath("$.rawHex").value("00010000000501030204D2"))
                .andExpect(jsonPath("$.quality").value("GOOD"));

        mockMvc.perform(get("/api/readings/does-not-exist"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionState").value("CONNECTED"))
                .andExpect(jsonPath("$.remoteEndpoint").value("127.0.0.1:15020"))
                .andExpect(jsonPath("$.successfulConnections").value(1));
    }

    private static final class StubReadingStore extends ReadingStore {
        private List<ReadingSnapshot> readings = List.of();
        private final Map<String, ReadingSnapshot> byName = new java.util.HashMap<>();
        private GatewayStatusSnapshot status;

        private StubReadingStore() {
            super(new GatewayProperties(), Clock.systemUTC());
        }

        @Override
        public List<ReadingSnapshot> snapshots() {
            return readings;
        }

        @Override
        public ReadingSnapshot snapshot(String pointName) {
            return byName.get(pointName);
        }

        @Override
        public GatewayStatusSnapshot status() {
            return status;
        }
    }
}
