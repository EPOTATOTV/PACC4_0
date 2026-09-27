package com.potatotv.paccclient.rules;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.ai.ModelRepository;
import com.potatotv.paccclient.detection.cheat.PrlDetectionEngine;
import com.potatotv.paccclient.security.CodeIntegrityService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 端侧规则下发同步（设计文档 §3.2.4）：拉取 PTV 的规则清单，按需下载并热加载。
 *
 * <p>流程：{@code GET /api/player/rules/manifest}（玩家 JWT）→ 与本地已安装记录比对
 * （版本 + SHA-256）→ 变化时下载源码 → 校验 SHA-256 → {@link PrlDetectionEngine#updateRule}
 * 编译（编译不过就抛，旧规则原样保留）→ 原子写本地缓存 → 更新记录。灰度在服务端分桶完成，
 * 客户端不做任何灰度判断 —— 清单里有什么就装什么。</p>
 *
 * <p>降级约定与模型下发一致：网络失败、摘要不符、编译失败都只记录并保留旧规则，
 * 下一轮同步再试；已安装记录与缓存文件放在 {@code <数据目录>/rules}，记录只存版本与摘要。</p>
 *
 * <p><b>为什么启动时要 {@link #loadCache()}。</b>缓存下来的规则不写回 jar，重启后必须从磁盘
 * 重新装载，否则「上一轮同步成功、这一轮离线」的设备会悄悄退回到随包内置的旧规则。</p>
 */
public final class RuleSync {

    /** 已安装记录文件名（位于规则缓存目录内）。 */
    private static final String STATE_FILE = "installed-rules.json";
    private static final String MANIFEST_PATH = "/api/player/rules/manifest";
    private static final String RULE_SUFFIX = ".prl";

    private final String baseUrl;
    private final String token;
    private final PrlDetectionEngine engine;
    private final Path dir;
    private final HttpClient http;

    public RuleSync(String serverUri, String token, PrlDetectionEngine engine) {
        this(serverUri, token, engine, resolveDir());
    }

    public RuleSync(String serverUri, String token, PrlDetectionEngine engine, Path dir) {
        String base = serverUri == null ? "" : serverUri;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.token = token == null ? "" : token;
        this.engine = engine;
        this.dir = dir;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * 解析规则缓存目录：环境变量 {@code PACC_RULE_DIR} 优先，否则取客户端数据目录下的 {@code rules}。
     *
     * <p>复用 {@link ModelRepository#resolveDir()} 而不是再抄一份平台判断：数据目录只有一处定义，
     * 三份拷贝迟早会出现「模型在 A 目录、规则在 B 目录」这种只有运维才发现的偏差。</p>
     */
    public static Path resolveDir() {
        String env = System.getenv("PACC_RULE_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return ModelRepository.resolveDir().resolve("rules");
    }

    /**
     * 执行一轮同步。
     *
     * @return 本次实际替换掉的规则条数（0 表示均无变化或同步失败）
     */
    public int syncOnce() {
        List<Map<String, Object>> rules = fetchManifest();
        if (rules.isEmpty()) {
            return 0;
        }
        Map<String, String> installed = readState();
        int changed = 0;
        boolean stateDirty = false;
        for (Map<String, Object> rule : rules) {
            String name = str(rule.get("name"));
            if (name.isBlank()) {
                continue;
            }
            String version = str(rule.get("version"));
            String sha = str(rule.get("sha256")).toLowerCase(Locale.ROOT);
            if (unchanged(installed, name, version, sha)) {
                continue;
            }
            if (download(name, str(rule.get("url")), version, sha)) {
                installed.put(key(name, "version"), version);
                installed.put(key(name, "sha256"), sha);
                changed++;
                stateDirty = true;
            }
        }
        if (stateDirty) {
            writeState(installed);
        }
        return changed;
    }

    /**
     * 装载本地缓存的规则（启动时在 {@link PrlDetectionEngine#loadBuiltin()} 之后调用）。
     *
     * <p>单条失败只跳过它自己：一条缓存损坏不该让整批下发来的规则全部失效。</p>
     *
     * @return 成功装载的条数
     */
    public int loadCache() {
        Map<String, String> state = readState();
        int applied = 0;
        for (Map.Entry<String, String> entry : state.entrySet()) {
            String name = ruleNameOf(entry.getKey());
            if (name == null) {
                continue;
            }
            Path file = ruleFile(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                engine.updateRule(name, Files.readString(file, StandardCharsets.UTF_8));
                applied++;
            } catch (IOException | RuntimeException e) {
                System.err.println("[PTV-PRL] 缓存规则装载失败 " + name + ": " + e.getMessage());
            }
        }
        return applied;
    }

    /** 拉取清单；网络/鉴权失败或响应不含规则时返回空列表（调用方按「无变化」处理）。 */
    private List<Map<String, Object>> fetchManifest() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + MANIFEST_PATH))
                .header("Authorization", "Bearer " + token)
                .GET().timeout(Duration.ofSeconds(8)).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2 || r.body() == null) {
                return List.of();
            }
            Object list = Json.decodeObject(r.body()).get("rules");
            if (!(list instanceof List<?> raw)) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>(raw.size());
            for (Object o : raw) {
                if (o instanceof Map<?, ?> mm) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    mm.forEach((k, v) -> one.put(str(k), v));
                    out.add(one);
                }
            }
            return out;
        } catch (Exception e) {
            System.err.println("[PTV-PRL] 规则清单拉取失败: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * 下载一条规则并热加载；任一环节失败返回 false（保留旧规则）。
     *
     * <p>顺序刻意是「校验 → 编译 → 落盘」：先落盘再编译的话，一份编译不过的源码会覆盖掉本地
     * 唯一可用的缓存，设备重启后就连旧规则都没了。</p>
     */
    private boolean download(String name, String path, String version, String expectSha) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String url = path.startsWith("http") ? path : baseUrl + path;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .GET().timeout(Duration.ofSeconds(20)).build();
            HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() / 100 != 2) {
                System.err.println("[PTV-PRL] 规则下载失败 HTTP " + r.statusCode() + " url=" + url);
                return false;
            }
            byte[] raw = r.body();
            if (raw == null || raw.length == 0) {
                return false;
            }
            if (!expectSha.isEmpty() && !expectSha.equals(CodeIntegrityService.sha256Hex(raw))) {
                System.err.println("[PTV-PRL] 规则下载校验失败（SHA-256 不一致），已放弃更新 " + name);
                return false;
            }
            String source = new String(raw, StandardCharsets.UTF_8);
            // 编译通过才替换：updateRule 失败时抛异常，引擎里的旧规则不受影响
            engine.updateRule(name, source);
            if (!writeRuleFile(name, raw)) {
                // 内存里已经生效，只是重启后会退回内置版本，不值得因此回滚这次更新
                System.err.println("[PTV-PRL] 规则缓存写入失败，本次生效但不持久化 " + name);
            }
            System.out.println("[PTV-PRL] 规则已更新 " + name + " v" + version);
            return true;
        } catch (Exception e) {
            System.err.println("[PTV-PRL] 规则更新失败 " + name + ": " + e.getMessage());
            return false;
        }
    }

    // ------------------------------ 本地缓存 ------------------------------

    private boolean unchanged(Map<String, String> installed, String name, String version, String sha) {
        if (!version.equals(installed.get(key(name, "version")))) {
            return false;
        }
        if (!sha.isEmpty() && !sha.equals(installed.get(key(name, "sha256")))) {
            return false;
        }
        // 记录在但文件被清理过：重新下载，避免「记录说已安装、实际没有缓存」
        return Files.isRegularFile(ruleFile(name));
    }

    /** 原子替换缓存文件：临时文件 + ATOMIC_MOVE，避免断电/崩溃留下半截源码。 */
    private boolean writeRuleFile(String name, byte[] raw) {
        try {
            Files.createDirectories(dir);
            Path target = ruleFile(name);
            // 临时文件也用收紧后的名字：规则名来自网络，不能把它直接交给 createTempFile
            Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
            Files.write(tmp, raw);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            System.err.println("[PTV-PRL] 规则缓存写入异常 " + name + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 读取已安装记录。键为 {@code 规则名.字段} 的扁平形式（如 {@code fastplace.version}）：
     * 客户端自带的极简 JSON 编码器只支持一层对象，扁平键既省一次编码实现，也便于人眼核对。
     */
    private Map<String, String> readState() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Path f = dir.resolve(STATE_FILE);
            if (!Files.isRegularFile(f)) {
                return out;
            }
            for (Map.Entry<String, Object> e : Json.decodeObject(Files.readString(f)).entrySet()) {
                out.put(e.getKey(), str(e.getValue()));
            }
        } catch (Exception e) {
            System.err.println("[PTV-PRL] 规则记录读取失败（按未安装处理）: " + e.getMessage());
        }
        return out;
    }

    private void writeState(Map<String, String> installed) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(STATE_FILE),
                    Json.encode(new LinkedHashMap<String, Object>(installed)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 记录写失败只会导致下一轮重复下载，不影响检测
            System.err.println("[PTV-PRL] 规则记录写入失败: " + e.getMessage());
        }
    }

    /** 缓存文件名；规则名来自网络，落盘前收紧成字符白名单。 */
    private Path ruleFile(String name) {
        return dir.resolve(name.replaceAll("[^0-9A-Za-z_-]", "_") + RULE_SUFFIX);
    }

    private static String key(String name, String field) {
        return name + "." + field;
    }

    /** 从 {@code 规则名.version} 形式的记录键里取规则名；不是版本键则返回 {@code null}。 */
    private static String ruleNameOf(String recordKey) {
        int dot = recordKey.lastIndexOf('.');
        return dot > 0 && "version".equals(recordKey.substring(dot + 1))
                ? recordKey.substring(0, dot)
                : null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}