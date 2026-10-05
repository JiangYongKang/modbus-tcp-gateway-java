package com.github.highcumontoa.gateway.web;

import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.fault.FaultRegistry;
import com.github.highcumontoa.gateway.simulator.LocalModbusDeviceServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeviceControllerTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalModbusDeviceServer device =
                new LocalModbusDeviceServer(new GatewayProperties(), new FaultRegistry());
        mockMvc = MockMvcBuilders.standaloneSetup(new DeviceController(device)).build();
    }

    @Test
    void readsWritesAndResetsRegistersOverHttp() throws Exception {
        mockMvc.perform(get("/api/device/registers?type=HOLDING&address=0&quantity=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values[0]").value(0x04D2))
                .andExpect(jsonPath("$.hex").value("04D21234"));

        mockMvc.perform(put("/api/device/registers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registerType\":\"HOLDING\",\"address\":0,"
                                + "\"values\":[16,32]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(2));

        mockMvc.perform(get("/api/device/registers?type=HOLDING&address=0&quantity=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hex").value("00100020"));

        mockMvc.perform(post("/api/device/registers/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset").value(true));

        mockMvc.perform(get("/api/device/registers?type=HOLDING&address=0&quantity=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hex").value("04D21234"));
    }

    @Test
    void rejectsOutOfRangeWrites() throws Exception {
        mockMvc.perform(put("/api/device/registers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registerType\":\"HOLDING\",\"address\":63,"
                                + "\"values\":[1,2]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/device/registers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registerType\":\"HOLDING\",\"address\":0,"
                                + "\"values\":[65536]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/device/registers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registerType\":\"HOLDING\",\"address\":0,\"values\":[]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/device/registers?type=HOLDING&address=60&quantity=10"))
                .andExpect(status().isBadRequest());
    }
}
