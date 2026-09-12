package com.potatotv.pacc.mobile.plugin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 开机自启与省电白名单插件（设计文档 §4.2.2 AutoStart）。
 * 开机自启动通过 BootReceiver 完成；本插件提供电池优化白名单申请与状态查询。
 */
@CapacitorPlugin(name = "PaccAutoStart")
public class PaccAutoStartPlugin extends Plugin {

    @PluginMethod
    public void isIgnoringBatteryOptimizations(PluginCall call) {
        JSObject out = new JSObject();
        out.put("ignoring", isIgnoring(getContext()));
        call.resolve(out);
    }

    /** 拉起系统设置页申请白名单（需要用户手动确认）。 */
    @PluginMethod
    @SuppressLint("BatteryLife")
    public void requestIgnoreBatteryOptimizations(PluginCall call) {
        if (isIgnoring(getContext())) {
            call.resolve();
            return;
        }
        Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getContext().getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("无法打开电池优化设置页: " + e.getMessage());
        }
    }

    private boolean isIgnoring(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }
}
