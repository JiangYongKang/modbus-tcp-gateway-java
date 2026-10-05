package com.github.highcumontoa.gateway.web;

import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.fault.FaultRegistry;
import com.github.highcumontoa.gateway.model.DataType;
import com.github.highcumontoa.gateway.model.RegisterType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FaultControllerTest {
    private MockMvc mockMvc;
    private FaultRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new FaultRegistry();
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.PointDefinition point = new GatewayProperties.PointDefinition();
        point.setName("temperature-c");
        point.setRegisterType(RegisterType.HOLDING);
        point.setAddress(0);
        point.setDataType(DataType.INT16);
        properties.setPoints(List.of(point));
        mockMvc = MockMvcBuilders.standaloneSetup(new FaultController(registry, properties)).build();
    }

    @Test
    void injectsListsAndRevokesFaultsOverHttp() throws Exception {
        mockMvc.perform(post("/api/faults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pointName\":\"temperature-c\",\"type\":\"TIMEOUT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.registerType").value("HOLDING"))
                .andExpect(jsonPath("$.address").value(0))
                .andExpect(jsonPath("$.span").value(1))
                .andExpect(jsonPath("$.type").value("TIMEOUT"));

        mockMvc.perform(get("/api/faults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("TIMEOUT"));

        String id = registry.list().get(0).id();
        mockMvc.perform(delete("/api/faults/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(id));

        mockMvc.perform(delete("/api/faults/" + id))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/faults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectsInvalidInjectionRequests() throws Exception {
        mockMvc.perform(post("/api/faults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pointName\":\"temperature-c\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/faults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pointName\":\"no-such-point\",\"type\":\"TIMEOUT\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/faults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"TIMEOUT\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/faults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registerType\":\"INPUT\",\"address\":3,\"span\":2,"
                                + "\"type\":\"MODBUS_EXCEPTION\",\"exceptionCode\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registerType").value("INPUT"))
                .andExpect(jsonPath("$.address").value(3))
                .andExpect(jsonPath("$.span").value(2))
                .andExpect(jsonPath("$.exceptionCode").value(2));

        mockMvc.perform(delete("/api/faults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(1));
    }
}
