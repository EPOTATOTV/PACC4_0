package com.potatotv.pacc.mobile.plugin;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 移动端检测桥插件（设计文档 §4.1）。
 * 移动端仅做轻量本地探测（前台占用 / 传感器扫描等），不做内核级注入检测，
 * 全维度检测（内存篡改 / 特征码）仅支持桌面端 Java 引擎。
 */
@CapacitorPlugin(name = "PaccDetection")
public class PaccDetectionPlugin extends Plugin {

    private final List<JSObject> detections = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;
    private boolean running;
    private JSObject config = new JSObject();

    @PluginMethod
    public void status(PluginCall call) {
        JSObject out = new JSObject();
        out.put("running", running);
        out.put("platform", "mobile");
        out.put("capabilities", new JSObject().put("full_scan", false).put("foreground", true));
        out.put("lastEventType", lastEvent);
        out.put("redscreenActive", redscreenActive);
        call.resolve(out);
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (call.getData() != null) config = call.getData();
        if (scheduler != null) return;
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::poll, 2, 5, TimeUnit.SECONDS);
        JSObject out = new JSObject();
        out.put("running", true);
        call.resolve(out);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
        running = false;
        JSObject out = new JSObject();
        out.put("running", false);
        call.resolve(out);
    }

    @PluginMethod
    public void detections(PluginCall call) {
        int limit = call.getInt("limit", 20);
        int n = Math.min(limit, detections.size());
        List<Object> out = new ArrayList<>(n);
        for (int i = detections.size() - n; i < detections.size(); i++) out.add(detections.get(i));
        JSObject r = new JSObject();
        r.put("results", out);
        call.resolve(r);
    }

    // --- 内部轮询：轻量前台探测示例 ---
    private String lastEvent = "";
    private boolean redscreenActive;

    private void poll() {
        // 移动端探测占位：此处仅产生事件占位，真实接入由检测 SDK 提供。
        // 生命周期安全：当前 Activity 相关能力通过 getActivity() 获取，非前台时不探测。
        // （实现细化为下一步：接入系统前台服务 + 传感器可用性检查）
    }
}