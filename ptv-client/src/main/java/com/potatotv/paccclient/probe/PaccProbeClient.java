package com.potatotv.paccclient.probe;

import com.potatotv.paccclient.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PaccManager（.NET）原生探针的回环 HTTP 客户端（文档 §4.3 / §5.3）。
 *
 * <p>Java 侧不直接做用户态内存扫描与模块枚举，改由桌面壳 {@code PaccManager} 提供原生能力，
 * 通过 {@code 127.0.0.1} 回环接口取回结果。端点默认 {@code http://127.0.0.1:17020}，可用系统属性
 * {@code pacc.probe.endpoint} 覆盖（该端口也被 Java Agent 探针占用，实际部署应错开或复用同一进程）。</p>
 *
 * <p>接口契约（与 {@code tools/windows-gui/SystemProbeEndpoint.cs} 一一对应）：</p>
 * <pre>
 * GET /probe/health
 *   → 200 任意体
 *
 * GET /probe/modules?process={name}
 *   → {"process":"...","modules":[{"name":"...","path":"...","base":123,"size":456}]}
 *
 * GET /probe/memory?process={name}&signature={id}&pattern={hex}&mask={hex}
 *   → {"signature":"...","supported":true,"addresses":[123,456],"note":"..."}
 *
 * GET /probe/signature?process={name}
 *   → {"process":"...","supported":true,"modules":[{"path":"...","valid":false,"publisher":null,"note":null}]}
 *
 * GET /probe/verify?paths={p1;p2;...}
 *   → {"supported":true,"files":[{"path":"...","valid":true,"publisher":"...","note":null}]}
 *
 * GET /probe/injection?process={name}
 *   → {"process":"...","supported":true,"remoteThreadCount":0,"execRwRegionCount":0,
 *      "pendingApc":false,"suspiciousHandleCount":0,"note":"..."}
 * </pre>
 *
 * <p>所有调用失败（不可达 / 超时 / 非 200 / 解析失败）都返回空结果，绝不抛出 —— 探针是尽力而为，
 * 不可用时应让上层优雅降级，而不是让检测器挂掉。</p>
 */
public final class PaccProbeClient {

    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:17020";
    private static final int CONNECT_TIMEOUT_MS = 300;
    private static final int READ_TIMEOUT_MS = 1500;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final long REACHABLE_TTL_MS = 5_000L;

    private final String endpoint;
    private volatile boolean lastReachable;
    private volatile long lastCheckAt;

    public PaccProbeClient() {
        this(System.getProperty("pacc.probe.endpoint", DEFAULT_ENDPOINT));
    }

    public PaccProbeClient(String endpoint) {
        String value = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        this.endpoint = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** 探针端点（供日志 / 诊断）。 */
    public String endpoint() {
        return endpoint;
    }

    /** 探针是否可达（结果带 5s TTL 缓存，避免每个检测器都发一次请求）。 */
    public boolean reachable() {
        long now = System.currentTimeMillis();
        if (now - lastCheckAt < REACHABLE_TTL_MS) {
            return lastReachable;
        }
        lastReachable = httpGet("/probe/health") != null;
        lastCheckAt = now;
        return lastReachable;
    }

    /** 枚举指定进程的已加载模块。 */
    public ModuleSnapshot modules(String processName) {
        String name = processName == null ? "" : processName;
        String body = httpGet("/probe/modules?process=" + encode(name));
        if (body == null) {
            return ModuleSnapshot.empty(name);
        }
        List<ModuleSnapshot.ModuleInfo> modules = new ArrayList<>();
        try {
            Map<String, Object> obj = Json.decodeObject(body);
            if (obj.get("modules") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        modules.add(new ModuleSnapshot.ModuleInfo(
                                str(m.get("name")), str(m.get("path")), num(m.get("base")), num(m.get("size"))));
                    }
                }
            }
        } catch (RuntimeException e) {
            return ModuleSnapshot.empty(name);
        }
        return new ModuleSnapshot(name, modules);
    }

    /**
     * 用原生探针做一次用户态内存特征码扫描。
     *
     * @param processName 目标进程名
     * @param signatureId 特征码标识（可为 null，仅作取证标记）
     * @param pattern     特征码字节
     * @param mask        掩码，{@code 0xFF} 表示该字节必须相等，{@code 0x00} 表示通配
     */
    public MemoryScanResult scan(String processName, String signatureId, byte[] pattern, byte[] mask) {
        String name = processName == null ? "" : processName;
        String body = httpGet("/probe/memory?process=" + encode(name)
                + "&signature=" + encode(signatureId == null ? "" : signatureId)
                + "&pattern=" + toHex(pattern)
                + "&mask=" + toHex(mask));
        if (body == null) {
            return MemoryScanResult.unsupported(signatureId, "PaccManager 探针不可达");
        }
        try {
            Map<String, Object> obj = Json.decodeObject(body);
            boolean supported = Boolean.TRUE.equals(obj.get("supported"));
            List<Long> addresses = new ArrayList<>();
            if (obj.get("addresses") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Number n) {
                        addresses.add(n.longValue());
                    }
                }
            }
            return new MemoryScanResult(signatureId, supported, addresses, str(obj.get("note")));
        } catch (RuntimeException e) {
            return MemoryScanResult.unsupported(signatureId, "探针响应解析失败");
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 验证指定进程已加载模块的数字签名（文档 §4.2）。探针不可达时返回空表。 */
    public List<SignatureResult> verifyModules(String processName) {
        String name = processName == null ? "" : processName;
        String body = httpGet("/probe/signature?process=" + encode(name));
        if (body == null) {
            return List.of();
        }
        return parseSignatureList(body, "modules");
    }

    /** 验证一批文件的数字签名（文档 §4.5）。探针不可达时返回空表。 */
    public List<SignatureResult> verifyFiles(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        StringBuilder joined = new StringBuilder();
        for (Path file : files) {
            if (file == null) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(';');
            }
            joined.append(file.toAbsolutePath());
        }
        if (joined.length() == 0) {
            return List.of();
        }
        String body = httpGet("/probe/verify?paths=" + encode(joined.toString()));
        if (body == null) {
            return List.of();
        }
        return parseSignatureList(body, "files");
    }

    /** 检测指定进程的注入痕迹（文档 §4.3）。探针不可达时返回不支持状态。 */
    public InjectionReport detectInjection(String processName) {
        String name = processName == null ? "" : processName;
        String body = httpGet("/probe/injection?process=" + encode(name));
        if (body == null) {
            return InjectionReport.unsupported("PaccManager 探针不可达");
        }
        try {
            Map<String, Object> obj = Json.decodeObject(body);
            return new InjectionReport(
                    Boolean.TRUE.equals(obj.get("supported")),
                    (int) num(obj.get("remoteThreadCount")),
                    (int) num(obj.get("execRwRegionCount")),
                    Boolean.TRUE.equals(obj.get("pendingApc")),
                    (int) num(obj.get("suspiciousHandleCount")),
                    str(obj.get("note")));
        } catch (RuntimeException e) {
            return InjectionReport.unsupported("探针响应解析失败");
        }
    }

    private static List<SignatureResult> parseSignatureList(String body, String arrayKey) {
        List<SignatureResult> out = new ArrayList<>();
        try {
            Map<String, Object> obj = Json.decodeObject(body);
            if (obj.get(arrayKey) instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        out.add(new SignatureResult(
                                str(m.get("path")),
                                Boolean.TRUE.equals(m.get("valid")),
                                str(m.get("publisher")),
                                str(m.get("note"))));
                    }
                }
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return out;
    }

    private String httpGet(String path) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(endpoint + path).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return new String(in.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}