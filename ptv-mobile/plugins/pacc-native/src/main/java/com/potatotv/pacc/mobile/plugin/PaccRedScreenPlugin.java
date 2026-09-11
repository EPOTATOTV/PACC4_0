package com.potatotv.pacc.mobile.plugin;

import android.content.Intent;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 移动端红屏警告插件（设计文档 §6.2.1）。
 * 前端调用 show/dismiss 拉起或关闭全屏红屏 Activity。
 */
@CapacitorPlugin(name = "PaccRedScreen")
public class PaccRedScreenPlugin extends Plugin {

    private static final String RSA_TAG = "PaccRedScreen";

    @PluginMethod
    public void show(PluginCall call) {
        int level = call.getInt("level", 2);
        String reason = call.getString("reason");
        if (reason == null || reason.isEmpty()) reason = "检测到作弊行为，已暂停活动";

        Intent intent = new Intent(getContext(), RedscreenActivity.class);
        intent.putExtra("level", Math.max(1, Math.min(level, 4)));
        intent.putExtra("reason", reason);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        getContext().startActivity(intent);
        call.resolve();
    }

    /** 仅管理员远程解除时调用。 */
    @PluginMethod
    public void dismiss(PluginCall call) {
        RedscreenActivity.finishActive();
        call.resolve();
    }
}