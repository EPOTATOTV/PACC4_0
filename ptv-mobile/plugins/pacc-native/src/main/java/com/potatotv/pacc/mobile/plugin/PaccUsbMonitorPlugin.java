package com.potatotv.pacc.mobile.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * USB 设备监控插件（设计文档 §4.2.2 USBDeviceMonitor）。
 * 枚举已连接的 USB 设备，检测可疑设备（DMA 采集卡、可编程鼠标、手柄转换器等）。
 * 移动端 USB Host 能力有限，主要用于识别外设接入并上报服务端研判。
 */
@CapacitorPlugin(name = "PaccUsbMonitor")
public class PaccUsbMonitorPlugin extends Plugin {

    @PluginMethod
    public void devices(PluginCall call) {
        UsbManager usb = (UsbManager) getContext().getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> list = usb == null ? new HashMap<>() : usb.getDeviceList();
        List<Object> out = new ArrayList<>(list.size());
        for (UsbDevice d : list.values()) {
            JSObject o = new JSObject();
            o.put("name", d.getDeviceName());
            o.put("vendorId", d.getVendorId());
            o.put("productId", d.getProductId());
            o.put("deviceClass", d.getDeviceClass());
            o.put("deviceSubclass", d.getDeviceSubclass());
            o.put("manufacturer", d.getManufacturerName());
            o.put("productName", d.getProductName());
            out.add(o);
        }
        JSObject r = new JSObject();
        r.put("devices", out);
        r.put("count", out.size());
        call.resolve(r);
    }

    /** 注册 USB 插拔广播，前端通过 addListener 订阅 usb_attached/usb_detached。 */
    @Override
    public void load() {
        super.load();
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        getContext().registerReceiver(usbReceiver, filter);
    }

    @Override
    protected void handleOnDestroy() {
        try {
            getContext().unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException ignored) {
            // 未注册时忽略
        }
        super.handleOnDestroy();
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null) return;
            JSObject payload = new JSObject();
            payload.put("vendorId", device.getVendorId());
            payload.put("productId", device.getProductId());
            payload.put("deviceName", device.getDeviceName());
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                notifyListeners("usb_attached", payload);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                notifyListeners("usb_detached", payload);
            }
        }
    };
}
