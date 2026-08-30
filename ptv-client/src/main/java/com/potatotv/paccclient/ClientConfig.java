package com.potatotv.paccclient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 玩家端配置加载：从应用目录 pacc-client.properties 或 classpath 读取。
 * <p>演示模式会自动登录 PTV 换取真实 JWT；生产可将令牌放入本地安全存储。</p>
 */
public final class ClientConfig {

    public final String pteid;
    public final String token;
    public final String edition;
    public final String wssUri;
    public final String serverUri;
    public final int heartbeatSeconds;
    public final int clientRisk;
    public final boolean demo;
    public final boolean demoLogin;
    public final String identity;
    public final String password;
    public final boolean remember;
    public final int reconnectDelaySeconds;
    public final boolean autoReconnect;
    public final String signatureVersion;
    /** WSS 上报签名密钥：与服务器端 PACC_WSS_SIGN_SECRET 保持一致时启用 HMAC 签名 + 防重放。 */
    public final String wssSignSecret;

    private ClientConfig(Properties p) {
        this.pteid = get(p, "pacc.client.pteid", "PACC_CLIENT_PTEID", "PT0000000001");
        this.token = get(p, "pacc.client.token", "PACC_CLIENT_TOKEN", "demo-access-token");
        this.edition = get(p, "pacc.client.edition", "PACC_CLIENT_EDITION", "JAVA");
        this.wssUri = get(p, "pacc.client.wss.uri", "PACC_CLIENT_WSS_URI", "ws://localhost:8080/ws/ptv");
        this.serverUri = get(p, "pacc.client.server.uri", "PACC_CLIENT_SERVER_URI", "http://localhost:8080");
        this.heartbeatSeconds = getInt(p, "pacc.client.heartbeat.seconds", "PACC_CLIENT_HEARTBEAT_SECONDS", 15);
        this.clientRisk = getInt(p, "pacc.client.client.risk", "PACC_CLIENT_RISK", 58);
        this.demo = getBool(p, "pacc.client.demo", "PACC_CLIENT_DEMO", true);
        this.demoLogin = getBool(p, "pacc.client.demo.login", "PACC_CLIENT_DEMO_LOGIN", true);
        this.identity = get(p, "pacc.client.identity", "PACC_CLIENT_IDENTITY", "demo@ptv.dev");
        this.password = get(p, "pacc.client.password", "PACC_CLIENT_PASSWORD", "DemoPass123");
        this.remember = getBool(p, "pacc.client.remember", "PACC_CLIENT_REMEMBER", false);
        this.reconnectDelaySeconds = getInt(p, "pacc.client.reconnect.delay.seconds", "PACC_CLIENT_RECONNECT_DELAY_SECONDS", 5);
        this.autoReconnect = getBool(p, "pacc.client.reconnect.enabled", "PACC_CLIENT_AUTO_RECONNECT", true);
        this.signatureVersion = get(p, "pacc.client.signature.version", "PACC_CLIENT_SIGNATURE_VERSION", "v4.0.0");
        this.wssSignSecret = get(p, "pacc.client.wss.sign.secret", "PACC_CLIENT_WSS_SECRET", "pacc-dev-wss-sign-key-change-me");
    }

    /** 环境变量优先，其次配置文件，最后内置默认值。 */
    private static String get(Properties p, String key, String env, String def) {
        String v = System.getenv(env);
        return (v != null && !v.isBlank()) ? v : p.getProperty(key, def);
    }

    private static int getInt(Properties p, String key, String env, int def) {
        return Integer.parseInt(get(p, key, env, String.valueOf(def)));
    }

    private static boolean getBool(Properties p, String key, String env, boolean def) {
        return Boolean.parseBoolean(get(p, key, env, String.valueOf(def)));
    }

    public static ClientConfig load() {
        Properties p = new Properties();
        // 优先读工作目录下的配置文件
        Path ext = Path.of("pacc-client.properties");
        if (Files.exists(ext)) {
            try (InputStream in = Files.newInputStream(ext)) {
                p.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("读取外部配置失败", e);
            }
        } else {
            try (InputStream in = ClientConfig.class.getResourceAsStream("/pacc-client.properties")) {
                if (in != null) p.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("读取内置配置失败", e);
            }
        }
        return new ClientConfig(p);
    }

    /** 构建带身份参数的 WSS 握手地址（PTEID + 访问令牌 + edition）。 */
    public String buildConnectUri(String token, String pteid) {
        String sep = wssUri.contains("?") ? "&" : "?";
        return wssUri + sep + "pteid=" + pteid
                + "&token=" + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8)
                + "&edition=" + edition;
    }
}
