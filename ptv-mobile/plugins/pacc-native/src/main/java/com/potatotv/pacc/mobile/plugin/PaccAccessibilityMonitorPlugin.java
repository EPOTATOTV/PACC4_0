package com.potatotv.pacc.mobile.plugin;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 无障碍输入监控插件（设计文档 §4.2.2 AccessibilityMonitor）。
 * 通过 AccessibilityService 采集触摸/按键事件，检测异常输入模式（自动点击器等）。
 * 需要在系统设置中手动开启无障碍权限，插件仅做状态查询与事件缓冲。
 */
@CapacitorPlugin(name = "PaccAccessibilityMonitor")
public class PaccAccessibilityMonitorPlugin extends Plugin {

    /** 事件缓冲：最近 N 条触摸/按键事件，供前端分析展示。 */
    private static final List<JSObject> events = new CopyOnWriteArrayList<>();
    private static final int MAX_EVENTS = 200;

    /** 由 PaccAccessibilityService 回写，判断服务是否存活。 */
    static volatile boolean serviceAlive;

    @PluginMethod
    public void isEnabled(PluginCall call) {
        JSObject out = new JSObject();
        out.put("enabled", isServiceEnabled(getContext()));
        out.put("serviceAlive", serviceAlive);
        call.resolve(out);
    }

    @PluginMethod
    public void events(PluginCall call) {
        int limit = call.getInt("limit", 50);
        int n = Math.min(limit, events.size());
        List<Object> out = new ArrayList<>(n);
        for (int i = events.size() - n; i < events.size(); i++) out.add(events.get(i));
        JSObject r = new JSObject();
        r.put("events", out);
        call.resolve(r);
    }

    @PluginMethod
    public void clear(PluginCall call) {
        events.clear();
        call.resolve();
    }

    /** 供无障碍服务在事件回调中追加记录。 */
    static void record(AccessibilityEvent e) {
        // 只保留触摸/滑动/按键事件，避免窗口变化等噪音事件刷屏。
        int type = e.getEventType();
        boolean interesting = type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START
                || type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED;
        if (!interesting) return;

        JSObject o = new JSObject();
        o.put("type", Integer.toString(type));
        o.put("ts", System.currentTimeMillis());
        if (e.getClassName() != null) o.put("cls", e.getClassName().toString());
        // 事件时间戳序列（毫秒），供前端做点击间隔/连点频率分析。
        o.put("eventTime", e.getEventTime());
        events.add(o);
        if (events.size() > MAX_EVENTS) events.remove(0);
    }

    /** 系统无障碍设置里是否已启用本服务。 */
    public static boolean isServiceEnabled(Context context) {
        String expected = context.getPackageName() + "/"
                + PaccAccessibilityService.class.getName();
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            if (splitter.next().equalsIgnoreCase(expected)) return true;
        }
        return false;
    }
}
