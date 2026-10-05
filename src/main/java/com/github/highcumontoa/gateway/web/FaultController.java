package com.github.highcumontoa.gateway.web;

import com.github.highcumontoa.gateway.config.GatewayProperties;
import com.github.highcumontoa.gateway.decode.PointDecoder;
import com.github.highcumontoa.gateway.fault.FaultRegistry;
import com.github.highcumontoa.gateway.fault.FaultSpec;
import com.github.highcumontoa.gateway.fault.FaultType;
import com.github.highcumontoa.gateway.model.RegisterType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.List;
import java.util.Map;

/**
 * 运行期故障编排接口：向本地数据源注入/撤销故障，网关无需重启。
 */
@RestController
@RequestMapping("/api/faults")
public class FaultController {
    private final FaultRegistry registry;
    private final GatewayProperties properties;

    public FaultController(FaultRegistry registry, GatewayProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @GetMapping
    public List<FaultSpec> list() {
        return registry.list();
    }

    @PostMapping
    public FaultSpec inject(@RequestBody FaultInjectionRequest request) {
        return registry.inject(request.toSpec(properties));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> revoke(@PathVariable String id) {
        if (!registry.revoke(id)) {
            throw new ResponseStatusException(NOT_FOUND, "Unknown fault id: " + id);
        }
        return Map.of("revoked", id);
    }

    @DeleteMapping
    public Map<String, Object> clear() {
        List<FaultSpec> active = registry.list();
        registry.clear();
        return Map.of("revoked", active.size());
    }

    /**
     * 故障注入请求。target 二选一：
     * pointName（按数据点解析寄存器区间）或 registerType+address(+span)。
     */
    public record FaultInjectionRequest(
            String id,
            String pointName,
            RegisterType registerType,
            Integer address,
            Integer span,
            FaultType type,
            Long delayMs,
            Integer exceptionCode,
            Integer chunkSize,
            Long chunkDelayMs,
            Long times) {

        FaultSpec toSpec(GatewayProperties properties) {
            if (type == null) {
                throw new ResponseStatusException(BAD_REQUEST, "type is required");
            }
            RegisterType resolvedType = registerType;
            int resolvedAddress;
            int resolvedSpan;
            if (pointName != null && !pointName.isBlank()) {
                GatewayProperties.PointDefinition point = properties.getPoints().stream()
                        .filter(candidate -> candidate.getName().equals(pointName))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST,
                                "Unknown point: " + pointName));
                resolvedType = point.getRegisterType();
                resolvedAddress = point.getAddress();
                resolvedSpan = PointDecoder.requiredRegisters(point.getDataType());
            } else if (address != null) {
                if (address < 0 || address > 0xFFFF) {
                    throw new ResponseStatusException(BAD_REQUEST,
                            "address must be within 0..65535");
                }
                resolvedAddress = address;
                resolvedSpan = span == null ? 1 : span;
            } else {
                throw new ResponseStatusException(BAD_REQUEST,
                        "Either pointName or registerType+address is required");
            }
            if (resolvedSpan < 1) {
                throw new ResponseStatusException(BAD_REQUEST, "span must be >= 1");
            }
            return new FaultSpec(
                    id,
                    resolvedType,
                    resolvedAddress,
                    resolvedSpan,
                    type,
                    delayMs == null ? 0L : delayMs,
                    exceptionCode == null ? 0 : exceptionCode,
                    chunkSize == null ? 1 : chunkSize,
                    chunkDelayMs == null ? 0L : chunkDelayMs,
                    times == null ? FaultSpec.UNLIMITED : times);
        }
    }
}
