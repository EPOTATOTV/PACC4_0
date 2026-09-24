package com.potatotv.paccclient;

import com.potatotv.paccclient.store.LocalSecureStore;
import com.potatotv.paccclient.store.MachineFingerprint;

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
    /** 特征库包签名密钥：与服务器端 PACC_SIG_SECRET 保持一致，用于端侧校验热更新包。 */
    public final String sigSecret;
    /**
     * 是否启用 WSS 会话级动态密钥。
     * <p>默认关闭：服务端若未开启 {@code pacc.wss.session-key-required} 且未处理 session_init，
     * 客户端贸然改走 v2 会导致查端信令全部验签失败。等两端一起升级后再打开。</p>
     */
    public final boolean wssSessionKeyEnabled;

    /** 敏感值解密口令：仅用设备指纹（不绑 pteid，保持 wss/sig/token 设备全局可解）。 */
    private final String secretPassword = MachineFingerprint.hash();

    private ClientConfig(Properties p) {
        this.pteid = get(p, "pacc.client.pteid", "PACC_CLIENT_PTEID", "PT0000000001");
        this.token = getSecretOr(p, "pacc.client.token", null, "PACC_CLIENT_TOKEN", "demo-access-token");
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
        this.signatureVersion = get(p, "pacc.client.signature.version", "PACC_CLIENT_SIGNATURE_VERSION", "v5.0.0");
        // 两个签名密钥不提供内置默认值：产物中一旦出现明文默认密钥，
        // 任何拿到发行件的人都能伪造 WSS 上报与特征库包。缺失即拒绝启动（fail-closed）。
        this.wssSignSecret = requireSecret(p, "pacc.client.wss.sign.secret", "pacc.client.wss-secret",
                "PACC_CLIENT_WSS_SECRET");
        this.sigSecret = requireSecret(p, "pacc.client.signature.secret", null,
                "PACC_CLIENT_SIG_SECRET");
        this.wssSessionKeyEnabled = getBool(p, "pacc.client.wss.session-key.enabled",
                "PACC_CLIENT_WSS_SESSION_KEY_ENABLED", false);
    }

    /** 环境变量优先，其次配置文件，最后内置默认值。 */
    private static String get(Properties p, String key, String env, String def) {
        String v = System.getenv(env);
        return (v != null && !v.isBlank()) ? v : p.getProperty(key, def);
    }

    /**
     * 读取签名密钥：支持遗留 key 回退、环境变量优先，并对 "enc:" 前缀做本机指纹解密。
     * 取值顺序 环境变量 &gt; 配置文件 &gt; 遗留 key；全部缺失或解密失败时抛异常，
     * 由 {@link #load()} 调用方终止启动，绝不静默降级为公开默认值。
     */
    private String requireSecret(Properties p, String key, String legacyKey, String env) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) v = p.getProperty(key);
        if ((v == null || v.isBlank()) && legacyKey != null) v = p.getProperty(legacyKey);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少必需密钥 " + key + "（可用环境变量 " + env + " 注入）");
        }
        if (v.startsWith(LocalSecureStore.PREFIX)) {
            try {
                v = LocalSecureStore.decryptString(v, secretPassword);
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("密钥 " + key + " 解密失败（设备指纹已变化或配置被篡改）", e);
            }
        }
        if (v.isBlank()) {
            throw new IllegalStateException("密钥 " + key + " 解密结果为空");
        }
        return v;
    }

    /**
     * 读取可选的端到端加密值（如访问令牌）：支持 env 优先 + "enc:" 前缀本机解密，
     * 缺失时回落到内置默认（仅供 demo）。区别于 {@link #requireSecret} 的地方是
     * 它允许默认值——令牌不像签名密钥那样是全局防伪凭据。
     */
    private String getSecretOr(Properties p, String key, String legacyKey, String env, String def) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) v = p.getProperty(key);
        if ((v == null || v.isBlank()) && legacyKey != null) v = p.getProperty(legacyKey);
        if (v == null || v.isBlank()) return def;
        if (v.startsWith(LocalSecureStore.PREFIX)) {
            try {
                v = LocalSecureStore.decryptString(v, secretPassword);
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("值 " + key + " 解密失败（设备指纹已变化或配置被篡改）", e);
            }
        }
        return v;
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
