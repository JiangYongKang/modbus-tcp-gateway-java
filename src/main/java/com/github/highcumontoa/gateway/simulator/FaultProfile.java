package com.github.highcumontoa.gateway.simulator;

import com.github.highcumontoa.gateway.model.RegisterType;

public class FaultProfile {
    private String id;
    private String pointName;
    private FaultType type;
    private RegisterType registerType;
    private int addressStart;
    private int addressEnd;
    private long delayMs;
    private int exceptionCode = 0x04;
    private int fragmentCount = 3;
    private long fragmentDelayMs = 20;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPointName() { return pointName; }
    public void setPointName(String pointName) { this.pointName = pointName; }
    public FaultType getType() { return type; }
    public void setType(FaultType type) { this.type = type; }
    public RegisterType getRegisterType() { return registerType; }
    public void setRegisterType(RegisterType registerType) { this.registerType = registerType; }
    public int getAddressStart() { return addressStart; }
    public void setAddressStart(int addressStart) { this.addressStart = addressStart; }
    public int getAddressEnd() { return addressEnd; }
    public void setAddressEnd(int addressEnd) { this.addressEnd = addressEnd; }
    public long getDelayMs() { return delayMs; }
    public void setDelayMs(long delayMs) { this.delayMs = delayMs; }
    public int getExceptionCode() { return exceptionCode; }
    public void setExceptionCode(int exceptionCode) { this.exceptionCode = exceptionCode; }
    public int getFragmentCount() { return fragmentCount; }
    public void setFragmentCount(int fragmentCount) { this.fragmentCount = fragmentCount; }
    public long getFragmentDelayMs() { return fragmentDelayMs; }
    public void setFragmentDelayMs(long fragmentDelayMs) { this.fragmentDelayMs = fragmentDelayMs; }
}
