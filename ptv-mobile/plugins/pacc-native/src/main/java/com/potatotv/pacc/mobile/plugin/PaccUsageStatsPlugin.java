package com.potatotv.pacc.mobile.plugin;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * 使用情况统计插件（设计文档 §4.2.2 UsageStatsMonitor）。
 * 通过 UsageStatsManager 获取应用使用情况，检测后台运行的可疑应用（修改器、脚本工具等）。
 * 需要用户在系统设置中授予"使用情况访问权限"。
 */
@CapacitorPlugin(name = "PaccUsageStats")
public class PaccUsageStatsPlugin extends Plugin {

    @PluginMethod
    public void hasPermission(PluginCall call) {
        JSObject out = new JSObject();
        out.put("granted", hasUsageAccess(getContext()));
        call.resolve(out);
    }

    @PluginMethod
    public void apps(PluginCall call) {
        int days = Math.max(1, call.getInt("days", 1));
        int limit = Math.min(100, call.getInt("limit", 50));
        JSObject r = new JSObject();
        r.put("results", recentApps(getContext(), days, limit));
        call.resolve(r);
    }

    private boolean hasUsageAccess(Context context) {
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return false;
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -1);
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, cal.getTimeInMillis(), System.currentTimeMillis());
        return stats != null && !stats.isEmpty();
    }

    private List<Object> recentApps(Context context, int days, int limit) {
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        List<Object> out = new ArrayList<>();
        if (usm == null) return out;

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -days);
        long start = cal.getTimeInMillis();
        long end = System.currentTimeMillis();

        Map<String, UsageStats> map = usm.queryAndAggregateUsageStats(start, end);
        for (UsageStats st : map.values()) {
            String pkg = st.getPackageName();
            JSObject o = new JSObject();
            o.put("packageName", pkg);
            o.put("appName", appLabel(context, pkg));
            o.put("lastTimeUsed", st.getLastTimeUsed());
            o.put("totalTimeInForeground", st.getTotalTimeInForeground());
            out.add(o);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private String appLabel(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
