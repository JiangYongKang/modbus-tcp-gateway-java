package com.github.highcumontoa.gateway.web;

import com.github.highcumontoa.gateway.model.RegisterType;
import com.github.highcumontoa.gateway.simulator.LocalModbusDeviceServer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地数据源寄存器映像的运行期查看、改写与复位接口。
 */
@RestController
@RequestMapping("/api/device/registers")
public class DeviceController {
    private final LocalModbusDeviceServer device;

    public DeviceController(LocalModbusDeviceServer device) {
        this.device = device;
    }

    @GetMapping
    public Map<String, Object> read(@RequestParam(defaultValue = "HOLDING") RegisterType type,
                                    @RequestParam(defaultValue = "0") int address,
                                    @RequestParam(defaultValue = "8") int quantity) {
        try {
            int[] values = device.readRegisters(type, address, quantity);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("registerType", type);
            result.put("address", address);
            result.put("values", values);
            result.put("hex", hexDump(values));
            return result;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping
    public Map<String, Object> write(@RequestBody RegisterWriteRequest request) {
        if (request.values() == null || request.values().length == 0) {
            throw new ResponseStatusException(BAD_REQUEST, "values must not be empty");
        }
        RegisterType type = request.registerType() == null ? RegisterType.HOLDING : request.registerType();
        try {
            device.writeRegisters(type, request.address(), request.values());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registerType", type);
        result.put("address", request.address());
        result.put("written", request.values().length);
        return result;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        device.resetRegisters();
        return Map.of("reset", true);
    }

    private static String hexDump(int[] values) {
        StringBuilder builder = new StringBuilder();
        for (int value : values) {
            builder.append(String.format("%04X", value & 0xFFFF));
        }
        return builder.toString();
    }

    public record RegisterWriteRequest(RegisterType registerType, int address, int[] values) {
        @Override
        public boolean equals(Object other) {
            return other instanceof RegisterWriteRequest request
                    && registerType == request.registerType
                    && address == request.address
                    && Arrays.equals(values, request.values);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * (registerType == null ? 0 : registerType.hashCode()) + address)
                    + Arrays.hashCode(values);
        }

        @Override
        public String toString() {
            return "RegisterWriteRequest[registerType=" + registerType + ", address=" + address
                    + ", values=" + Arrays.toString(values) + ']';
        }
    }
}
