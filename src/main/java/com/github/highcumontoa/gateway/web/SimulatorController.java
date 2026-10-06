package com.github.highcumontoa.gateway.web;

import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.decode.PointDecoder;
import com.github.highcumontoa.gateway.model.RegisterType;
import com.github.highcumontoa.gateway.simulator.FaultOrchestrator;
import com.github.highcumontoa.gateway.simulator.FaultProfile;
import com.github.highcumontoa.gateway.simulator.LocalModbusDeviceServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {
    private static final Logger log = LoggerFactory.getLogger(SimulatorController.class);

    private final FaultOrchestrator orchestrator;
    private final LocalModbusDeviceServer deviceServer;
    private final GatewayProperties properties;

    public SimulatorController(FaultOrchestrator orchestrator, LocalModbusDeviceServer deviceServer,
                               GatewayProperties properties) {
        this.orchestrator = orchestrator;
        this.deviceServer = deviceServer;
        this.properties = properties;
    }

    @GetMapping("/faults")
    public List<FaultProfile> faults() {
        return orchestrator.faults();
    }

    @PostMapping("/faults")
    public FaultProfile addFault(@RequestBody FaultProfile profile) {
        requireDeviceRunning();
        resolvePointReference(profile);
        FaultProfile stored = orchestrator.addFault(profile);
        log.warn("FAULT_API_ADD id={} type={} point={}", stored.getId(), stored.getType(),
                stored.getPointName());
        return stored;
    }

    @DeleteMapping("/faults/{id}")
    public void removeFault(@PathVariable String id) {
        if (!orchestrator.removeFault(id)) {
            throw new ResponseStatusException(NOT_FOUND, "Unknown fault: " + id);
        }
    }

    @DeleteMapping("/faults")
    public void clearFaults() {
        orchestrator.clearFaults();
    }

    @GetMapping("/registers")
    public Map<String, int[]> registers() {
        requireDeviceRunning();
        Map<String, int[]> snapshot = new LinkedHashMap<>();
        snapshot.put("holding", deviceServer.snapshotRegisters(RegisterType.HOLDING));
        snapshot.put("input", deviceServer.snapshotRegisters(RegisterType.INPUT));
        return snapshot;
    }

    @PutMapping("/registers")
    public void overrideRegisters(@RequestBody List<RegisterOverride> overrides) {
        requireDeviceRunning();
        if (overrides == null || overrides.isEmpty()) {
            throw new IllegalArgumentException("At least one register override is required");
        }
        for (RegisterOverride override : overrides) {
            if (override.registerType() == null) {
                throw new IllegalArgumentException("registerType must not be null");
            }
            deviceServer.setRegister(override.registerType(), override.address(), override.value());
        }
    }

    @DeleteMapping("/registers")
    public void resetRegisters() {
        requireDeviceRunning();
        deviceServer.resetRegisters();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage() == null ? "invalid request" : e.getMessage());
    }

    private void requireDeviceRunning() {
        if (!deviceServer.isRunning()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "Local Modbus device is not running; enable gateway.local-source to use orchestration");
        }
    }

    private void resolvePointReference(FaultProfile profile) {
        if (profile == null || profile.getPointName() == null || profile.getPointName().isBlank()) {
            return;
        }
        GatewayProperties.PointDefinition point = properties.getPoints().stream()
                .filter(candidate -> candidate.getName().equals(profile.getPointName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown point name in fault profile: " + profile.getPointName()));
        profile.setRegisterType(point.getRegisterType());
        profile.setAddressStart(point.getAddress());
        profile.setAddressEnd(point.getAddress()
                + PointDecoder.requiredRegisters(point.getDataType()) - 1);
    }

    public record RegisterOverride(RegisterType registerType, int address, int value) {
    }
}
