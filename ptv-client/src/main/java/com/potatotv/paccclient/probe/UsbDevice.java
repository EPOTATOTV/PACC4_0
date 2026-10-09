package com.potatotv.paccclient.probe;

/**
 * USB 设备（文档 §4.7，仅采集 VID/PID 与描述，不做读写）。
 *
 * @param vidPid       形如 {@code VID_2341&PID_0043}
 * @param description  设备描述
 * @param manufacturer 厂商
 */
public record UsbDevice(String vidPid, String description, String manufacturer) {
}