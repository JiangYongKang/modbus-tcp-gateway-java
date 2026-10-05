package com.github.highcumontoa.gateway.fault;

import com.github.highcumontoa.gateway.model.RegisterType;

/**
 * 一条运行期故障编排规则。命中条件为寄存器区间相交：
 * 请求的 [startAddress, startAddress+quantity) 与规则的 [address, address+span) 相交即命中。
 */
public record FaultSpec(
        String id,
        RegisterType registerType,
        int address,
        int span,
        FaultType type,
        long delayMs,
        int exceptionCode,
        int chunkSize,
        long chunkDelayMs,
        long times) {

    public static final long UNLIMITED = -1L;

    public boolean matches(RegisterType requestType, int startAddress, int quantity) {
        if (registerType != null && registerType != requestType) {
            return false;
        }
        long ruleStart = address;
        long ruleEnd = ruleStart + Math.max(1, span);
        long requestStart = startAddress;
        long requestEnd = requestStart + Math.max(1, quantity);
        return requestStart < ruleEnd && ruleStart < requestEnd;
    }
}
