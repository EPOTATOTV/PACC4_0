package com.potatotv.paccclient.store;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 本机指纹（需加解密口令的组成部分）：由稳定系统属性 + 主机名派生，跨进程/重启保持稳定。
 * 仅用作本地密钥派生因子，不对外上报。
 */
public final class MachineFingerprint {

    private MachineFingerprint() {
    }

    public static String hash() {
        String raw = String.join("|",
                System.getProperty("os.name", "?"),
                System.getProperty("os.arch", "?"),
                System.getProperty("user.name", "?"),
                System.getProperty("user.home", "?"),
                hostname());
        return sha256Hex(raw);
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}