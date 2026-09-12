package com.potatotv.pacc.mobile.plugin;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍输入监控服务（设计文档 §4.2.2 AccessibilityMonitor）。
 * 采集触摸/按键事件并转发给 PaccAccessibilityMonitorPlugin 缓冲，
 * 异常输入模式（连点、固定间隔点击）的分析在前端完成。
 * 需用户在系统设置中手动开启"无障碍"权限。
 */
public class PaccAccessibilityService extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        PaccAccessibilityMonitorPlugin.record(event);
    }

    @Override
    public void onInterrupt() {
        // 系统打断（如权限被关闭）时仅更新存活标记。
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        PaccAccessibilityMonitorPlugin.serviceAlive = true;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        PaccAccessibilityMonitorPlugin.serviceAlive = false;
        return super.onUnbind(intent);
    }
}
