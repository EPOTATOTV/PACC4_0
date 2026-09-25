package com.potatotv.paccclient.ai;

import com.potatotv.paccclient.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 端侧模型下发同步（文档 §2.1.3）：拉取 PTV 的模型清单，按需下载并热加载。
 *
 * <p>流程：{@code GET /api/player/v52/model/manifest}（玩家 JWT）→ 与本地已安装记录比对
 * （版本 + SHA-256）→ 变化时下载产物 → 校验 SHA-256 与容器完整性 → 写签名文件 →
 * {@link ModelRepository#install} 原子替换 → 更新本地记录 → {@link ModelRepository#loadInto}
 * 热加载。服务端已按灰度分桶决定下发版本，客户端不做任何灰度判断。</p>
 *
 * <p>降级约定：网络失败、产物损坏、签名不合法都只记录并保留旧模型（不做任何破坏性操作），
 * 下一轮同步再试；已安装记录写在模型目录的 {@code installed-models.json}，仅记录版本与摘要。</p>
 */
public final class ModelSync {

    /** 已安装记录文件名（位于模型目录内）。 */
    private static final String STATE_FILE = "installed-models.json";
    private static final String MANIFEST_PATH = "/api/player/v52/model/manifest";

    private final String baseUrl;
    private final String token;
    private final ModelRepository repository;
    private final HttpClient http;

    public ModelSync(String serverUri, String token, ModelRepository repository) {
        String base = serverUri == null ? "" : serverUri;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.baseUrl = base;
        this.token = token == null ? "" : token;
        this.repository = repository;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * 执行一轮同步：安装所有变化中的模型并热加载。
     *
     * @param target 加载目标（可为 {@code null}，此时只落盘不加载）
     * @return 是否安装了至少一个模型（有变化）
     */
    public boolean syncOnce(LocalAiModel target) {
        List<Map<String, Object>> models = fetchManifest();
        if (models.isEmpty()) return false;

        Map<String, String> installed = readState();
        boolean changed = false;
        for (Map<String, Object> m : models) {
            int type = modelTypeOf(str(m.get("model_type")));
            if (type < 0) continue;
            String version = str(m.get("version"));
            String sha = str(m.get("sha256")).toLowerCase(Locale.ROOT);
            if (unchanged(installed, type, version, sha)) continue;
            if (download(type, str(m.get("url")), sha, str(m.get("signature")))) {
                installed.put(key(type, "version"), version);
                installed.put(key(type, "sha256"), sha);
                changed = true;
            }
        }
        if (changed) {
            writeState(installed);
            if (target != null) repository.loadInto(target);
        }
        return changed;
    }

    /** 拉取清单；网络/鉴权失败或响应不含模型时返回空列表（调用方按「无变化」处理）。 */
    private List<Map<String, Object>> fetchManifest() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + MANIFEST_PATH))
                .header("Authorization", "Bearer " + token)
                .GET().timeout(Duration.ofSeconds(8)).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2 || r.body() == null) return List.of();
            Object list = Json.decodeObject(r.body()).get("models");
            if (!(list instanceof List<?> raw)) return List.of();
            List<Map<String, Object>> out = new java.util.ArrayList<>(raw.size());
            for (Object o : raw) {
                if (o instanceof Map<?, ?> mm) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    mm.forEach((k, v) -> one.put(str(k), v));
                    out.add(one);
                }
            }
            return out;
        } catch (Exception e) {
            System.err.println("[PTV-Client] 模型清单拉取失败: " + e.getMessage());
            return List.of();
        }
    }

    /** 下载单个模型并安装；任一校验失败返回 false（保留旧模型）。 */
    private boolean download(int type, String path, String expectSha, String signature) {
        if (path == null || path.isBlank()) return false;
        String url = path.startsWith("http") ? path : baseUrl + path;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .GET().timeout(Duration.ofSeconds(20)).build();
            HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() / 100 != 2) {
                System.err.println("[PTV-Client] 模型下载失败 HTTP " + r.statusCode() + " url=" + url);
                return false;
            }
            byte[] raw = r.body();
            if (raw == null || raw.length == 0) return false;
            if (!expectSha.isEmpty() && !expectSha.equals(PaccModelFormat.sha256Hex(raw))) {
                System.err.println("[PTV-Client] 模型下载校验失败（SHA-256 不一致），已放弃安装");
                return false;
            }
            if (!writeSignature(type, signature)) return false;
            PaccModelFormat.ModelHeader header = PaccModelFormat.peekHeader(raw);
            if (!repository.install(raw, header)) {
                System.err.println("[PTV-Client] 模型安装被拒绝（容器或签名校验未通过）");
                return false;
            }
            System.out.println("[PTV-Client] 模型已更新 type=" + type + " dim=" + header.featureDim());
            return true;
        } catch (Exception e) {
            System.err.println("[PTV-Client] 模型下载异常: " + e.getMessage());
            return false;
        }
    }

    /** 写入签名文件：清单签名为空时删除旧签名（配置了公钥的客户端会因此拒绝安装，符合预期）。 */
    private boolean writeSignature(int type, String signature) {
        Path sig = repository.signatureFile(type);
        try {
            if (signature == null || signature.isBlank()) {
                Files.deleteIfExists(sig);
                return true;
            }
            byte[] decoded = Base64.getDecoder().decode(signature.trim());
            Files.createDirectories(repository.dir());
            Files.write(sig, decoded);
            return true;
        } catch (IllegalArgumentException e) {
            System.err.println("[PTV-Client] 模型签名解码失败，已放弃安装");
            return false;
        } catch (IOException e) {
            System.err.println("[PTV-Client] 模型签名写入失败: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------ 本地已安装记录 ------------------------------

    private boolean unchanged(Map<String, String> installed, int type, String version, String sha) {
        if (!version.equals(installed.get(key(type, "version")))) return false;
        if (!sha.isEmpty() && !sha.equals(installed.get(key(type, "sha256")))) return false;
        // 记录在但文件被清理过：重新下载，避免「记录已安装、实际无模型」
        return Files.isRegularFile(repository.modelFile(type));
    }

    /**
     * 读取已安装记录。键为 {@code 类型.字段} 的扁平形式（如 {@code XGBOOST.version}）：
     * 客户端自带的极简 JSON 编码器只支持一层对象，扁平键既省一次编码实现，也便于人眼核对。
     */
    private Map<String, String> readState() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Path f = repository.dir().resolve(STATE_FILE);
            if (!Files.isRegularFile(f)) return out;
            for (Map.Entry<String, Object> e : Json.decodeObject(Files.readString(f)).entrySet()) {
                out.put(e.getKey(), str(e.getValue()));
            }
        } catch (Exception e) {
            System.err.println("[PTV-Client] 模型记录读取失败（按未安装处理）: " + e.getMessage());
        }
        return out;
    }

    private void writeState(Map<String, String> installed) {
        try {
            Files.createDirectories(repository.dir());
            Files.writeString(repository.dir().resolve(STATE_FILE),
                    Json.encode(new LinkedHashMap<String, Object>(installed)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 记录写失败只会导致下一轮重复安装，不影响检测
            System.err.println("[PTV-Client] 模型记录写入失败: " + e.getMessage());
        }
    }

    private static String key(int type, String field) {
        return typeKey(type) + "." + field;
    }

    private static String typeKey(int type) {
        return switch (type) {
            case PaccModelFormat.TYPE_XGBOOST -> "XGBOOST";
            case PaccModelFormat.TYPE_AUTOENCODER -> "AUTOENCODER";
            default -> "LSTM_AE";
        };
    }

    /** 清单模型类型 → 容器类型；未知类型返回 -1（忽略，保证向前兼容）。 */
    private static int modelTypeOf(String name) {
        return switch (name == null ? "" : name.trim().toUpperCase(Locale.ROOT)) {
            case "XGBOOST", "XGB" -> PaccModelFormat.TYPE_XGBOOST;
            case "AUTOENCODER", "AE" -> PaccModelFormat.TYPE_AUTOENCODER;
            case "LSTM", "LSTM_AE" -> PaccModelFormat.TYPE_LSTM_AE;
            default -> -1;
        };
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}