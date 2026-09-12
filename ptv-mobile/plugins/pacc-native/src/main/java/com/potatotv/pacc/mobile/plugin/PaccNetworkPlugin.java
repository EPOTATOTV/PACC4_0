package com.potatotv.pacc.mobile.plugin;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 网络监控插件（设计文档 §4.2.2 NetworkMonitor）。
 * 移动端不建立本地 VPN（受系统限制且需用户授权），改为读取系统网络状态、
 * 连接类型与 DNS/网关信息，供服务端研判代理/VPN 类作弊工具。
 */
@CapacitorPlugin(name = "PaccNetwork")
public class PaccNetworkPlugin extends Plugin {

    @PluginMethod
    public void status(PluginCall call) {
        JSObject out = new JSObject();
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            out.put("available", false);
            call.resolve(out);
            return;
        }
        Network network = cm.getActiveNetwork();
        out.put("available", network != null);
        if (network != null) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            out.put("vpnActive", caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
            out.put("wifi", caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
            out.put("cellular", caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            out.put("metered", caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
            LinkProperties lp = cm.getLinkProperties(network);
            if (lp != null) {
                List<String> dns = new ArrayList<>();
                for (InetAddress a : lp.getDnsServers()) dns.add(a.getHostAddress());
                out.put("dnsServers", dns);
                List<String> ips = new ArrayList<>();
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address) ips.add(a.getHostAddress());
                }
                out.put("ipv4", ips);
            }
        }
        call.resolve(out);
    }
}
