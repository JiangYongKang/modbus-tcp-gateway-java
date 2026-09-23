package com.github.highcumontoa.modbustcpgatewayjava.web;

import com.github.highcumontoa.modbustcpgatewayjava.model.DeviceStatus;
import com.github.highcumontoa.modbustcpgatewayjava.model.PointReading;
import com.github.highcumontoa.modbustcpgatewayjava.service.GatewayManager;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外查询接口。
 *
 * <ul>
 *   <li>GET /api/status[/{deviceId}]：连接状态与各类计数、最近失败原因；</li>
 *   <li>GET /api/readings[/{deviceId}]：读数快照（值、质量标记、失败原因、
 *       原始报文 hex、采样时刻）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class GatewayController {

    private final GatewayManager gatewayManager;

    public GatewayController(GatewayManager gatewayManager) {
        this.gatewayManager = gatewayManager;
    }

    @GetMapping("/status")
    public List<DeviceStatus> allStatus() {
        return gatewayManager.getStore().getAllStatus();
    }

    @GetMapping("/status/{deviceId}")
    public DeviceStatus status(@PathVariable String deviceId) {
        DeviceStatus status = gatewayManager.getStore().getStatus(deviceId);
        if (status == null) {
            throw new DeviceNotFoundException(deviceId);
        }
        return status;
    }

    @GetMapping("/readings")
    public Map<String, List<PointReading>> allReadings() {
        return gatewayManager.getStore().getAllReadings();
    }

    @GetMapping("/readings/{deviceId}")
    public List<PointReading> readings(@PathVariable String deviceId) {
        if (gatewayManager.getStore().getStatus(deviceId) == null) {
            throw new DeviceNotFoundException(deviceId);
        }
        return gatewayManager.getStore().getReadings(deviceId);
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    static class DeviceNotFoundException extends RuntimeException {
        DeviceNotFoundException(String deviceId) {
            super("设备不存在: " + deviceId);
        }
    }
}
