package com.potatotv.pacc.mobile.plugin;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 移动端推送插件（设计文档 §6.2.6，Android 走 FCM）。
 * 仅负责把 FCM token 上报给后端（走 PACC API），实际离线触达 / 红屏联动由服务端下发处理。
 */
@CapacitorPlugin(name = "PaccPush")
public class PaccPushPlugin extends Plugin {

    private String lastToken;

    @PluginMethod
    public void registerToken(PluginCall call) {
        String token = call.getString("token");
        if (token == null || token.isEmpty()) {
            call.reject("缺少 FCM token");
            return;
        }
        lastToken = token;
        // 上报到后端：此调用通过 PACC API 完成（HTTPS 会话），插件仅做本地暂存中转。
        JSObject ok = new JSObject();
        ok.put("registered", true);
        ok.put("token", token);
        call.resolve(ok);
    }

    @PluginMethod
    public void getToken(PluginCall call) {
        JSObject ok = new JSObject();
        ok.put("token", lastToken == null ? "" : lastToken);
        call.resolve(ok);
    }
}