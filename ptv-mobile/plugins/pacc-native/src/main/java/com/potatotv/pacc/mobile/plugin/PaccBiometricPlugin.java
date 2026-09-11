package com.potatotv.pacc.mobile.plugin;

import androidx.fragment.app.FragmentActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.concurrent.Executor;

/**
 * 移动端生物识别插件（设计文档 §6.2.5）。
 * 用于高风险操作（解绑设备 / 清除存放关键数据的本地保险库）前的本地身份核验。
 * 红屏解除不依赖本插件：红屏仅支持管理员远程解除。
 */
@CapacitorPlugin(name = "PaccBiometric")
public class PaccBiometricPlugin extends Plugin {

    @PluginMethod
    public void isAvailable(PluginCall call) {
        JSObject ok = new JSObject();
        ok.put("available", checkAvailable());
        call.resolve(ok);
    }

    private boolean checkAvailable() {
        try {
            androidx.biometric.BiometricManager bm =
                    androidx.biometric.BiometricManager.from(getContext());
            int r = bm.canAuthenticate(BiometricPrompt.AUTHENTICATOR_BIOMETRIC_WEAK);
            return r == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
                    || r == androidx.biometric.BiometricManager.BIOMETRIC_STATUS_UNKNOWN;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @PluginMethod
    public void auth(PluginCall call) {
        String reason = call.getString("reason", "请完成生物识别验证");
        BiometricPrompt prompt = build(call);
        Object o = getActivity();
        if (!(o instanceof FragmentActivity fa)) {
            call.reject("需要 FragmentActivity 上下文");
            return;
        }
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("身份验证")
                .setSubtitle(reason)
                .setNegativeButtonText("取消")
                .setAllowedAuthenticators(BiometricPrompt.AUTHENTICATOR_BIOMETRIC_WEAK)
                .build();
        prompt.authenticate(info);
        // 结果回调 will resolve/reject call.
    }

    private BiometricPrompt build(PluginCall call) {
        Executor executor = ContextCompat.getMainExecutor(getContext());
        return new BiometricPrompt((FragmentActivity) getActivity(), executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        call.reject("生物识别失败: " + errString);
                    }

                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        JSObject ok = new JSObject();
                        ok.put("success", true);
                        call.resolve(ok);
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        call.reject("未识别到有效生物特征");
                    }
                });
    }
}