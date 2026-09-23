package com.github.highcumontoa.modbustcpgatewayjava.codec;

/**
 * 数据点解码失败：字节序/符号/宽度约定不适用、量程越界等。
 * 必须能定位到具体数据点。
 */
public class DecodeException extends Exception {

    private final String deviceId;
    private final String pointId;

    public DecodeException(String deviceId, String pointId, String message) {
        super(message);
        this.deviceId = deviceId;
        this.pointId = pointId;
    }

    public String deviceId() {
        return deviceId;
    }

    public String pointId() {
        return pointId;
    }
}
