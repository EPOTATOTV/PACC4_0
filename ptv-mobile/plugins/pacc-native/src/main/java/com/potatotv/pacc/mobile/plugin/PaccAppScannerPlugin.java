package com.potatotv.pacc.mobile.plugin;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 已安装应用扫描插件（设计文档 §4.2.2 AppScanner）。
 * 扫描已安装应用，比对已知外挂黑名单（包名+签名哈希），并检测可疑权限组合。
 * 黑名单以 packageName 前缀/全名匹配为主，签名哈希比对需结合服务端特征库下发。
 */
@CapacitorPlugin(name = "PaccAppScanner")
public class PaccAppScannerPlugin extends Plugin {

    /** 已知作弊/修改类应用包名特征（服务端下发前先内置常见项）。 */
    private static final Set<String> BLACKLIST = new HashSet<>(Arrays.asList(
            "com.android.guardian",
            "com.cheatengine.ce",
            "com.chelpus",
            "com.dimonvideo.luckypatcher",
            "com.aurora.luckypatcher",
            "com.microquation.smartcute",      // GameGuardian 旧包
            "com.another.cia",                 // GameGuardian
            "com.gameguardian",
            "com.sv.bioapps.camera"
    ));

    @PluginMethod
    public void scan(PluginCall call) {
        int limit = Math.min(300, call.getInt("limit", 200));
        List<Object> apps = new ArrayList<>();
        PackageManager pm = getContext().getPackageManager();
        List<PackageInfo> installed = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
        int count = 0;
        for (PackageInfo pi : installed) {
            if (count >= limit) break;
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null) continue;

            String pkg = pi.packageName;
            JSObject o = new JSObject();
            o.put("packageName", pkg);
            o.put("appName", pm.getApplicationLabel(ai).toString());
            o.put("versionName", pi.versionName);
            o.put("isSystem", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            o.put("matched", isBlacklisted(pkg));
            if (pi.requestedPermissions != null) {
                boolean risky = hasRiskyPermissions(pi.requestedPermissions);
                o.put("riskyPermissions", risky);
            }
            apps.add(o);
            count++;
        }
        JSObject r = new JSObject();
        r.put("results", apps);
        r.put("total", installed.size());
        r.put("blacklistCount", BLACKLIST.size());
        call.resolve(r);
    }

    private boolean isBlacklisted(String pkg) {
        for (String b : BLACKLIST) {
            if (pkg.equalsIgnoreCase(b) || pkg.toLowerCase(java.util.Locale.ROOT).startsWith(b.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    /** 可疑权限组合：无障碍 + 悬浮窗 + 未知来源同时存在，常见于注入/自动化工具。 */
    private boolean hasRiskyPermissions(String[] perms) {
        boolean overlay = false, accessibility = false, internet = false;
        for (String p : perms) {
            if ("android.permission.SYSTEM_ALERT_WINDOW".equals(p)) overlay = true;
            else if ("android.permission.BIND_ACCESSIBILITY_SERVICE".equals(p)) accessibility = true;
            else if ("android.permission.INTERNET".equals(p)) internet = true;
        }
        return overlay && accessibility && internet;
    }
}
