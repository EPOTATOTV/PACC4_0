package com.potatotv.paccclient.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.AnalysisContext;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.cheat.CheatFinding;
import com.potatotv.paccclient.detection.cheat.CheatType;
import com.potatotv.paccclient.detection.cheat.PrlDetectionEngine;
import com.potatotv.paccclient.security.CodeIntegrityService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 端侧规则下发同步（设计文档 §3.2.4）链路测试：清单驱动的下载 → 校验 → 编译 → 落盘，
 * 以及版本未变不重复下载、摘要不符与编译失败时保留旧规则、离线安静降级、重启后重装缓存。
 *
 * <p>观察点刻意分成两层：涉及「引擎里到底在跑什么」的断言用真实求值结果（构造一条只看
 * {@code temporal_anomaly} 的规则，不依赖任何特征采集），涉及「磁盘上留下了什么」的断言看缓存文件内容。
 * 只断言返回值 {@code syncOnce()} 是不够的：它返回 0 和「什么都没发生」看起来一样。</p>
 */
class RuleSyncTest {

    private static final String RULE = "fasteat";
    private static final String RULE_FILE = RULE + ".prl";
    private static final String STATE_FILE = "installed-rules.json";

    /** 只依据时序异常分命中，便于在单测里直接构造输入。 */
    private static final String SOURCE_V1 = source("1.0.0", 45.0);
    private static final String SOURCE_V2 = source("2.0.0", 70.0);

    @TempDir
    Path ruleDir;

    private HttpServer server;
    private PrlDetectionEngine engine;
    private volatile String version = "1.0.0";
    private volatile String served = SOURCE_V1;
    private volatile String servedSha = CodeIntegrityService.sha256Hex(bytes(SOURCE_V1));

    private final AtomicInteger manifestHits = new AtomicInteger();
    private final AtomicInteger fileHits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        engine = new PrlDetectionEngine();
        engine.loadBuiltin();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/player/rules/manifest", ex -> {
            manifestHits.incrementAndGet();
            respond(ex, 200, ("{\"rules\":[{\"name\":\"" + RULE + "\",\"version\":\"" + version
                    + "\",\"sha256\":\"" + servedSha + "\",\"status\":\"active\","
                    + "\"url\":\"/api/player/rules/" + RULE + "/file\"}]}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/api/player/rules/" + RULE + "/file", ex -> {
            fileHits.incrementAndGet();
            respond(ex, 200, bytes(served));
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ 正常下发

    @Test
    void 首次同步下载并热加载新规则() {
        // 前置条件：内置的 fasteat 看的是特征维度，这份只看时序异常分的输入打不动它
        assertTrue(hits().isEmpty(), "前置条件：内置版本在这一输入下不该命中");

        assertEquals(1, sync(), "首次同步应更新一条规则");

        // 引擎里换成了新版本：同样输入的加分从「不命中」变成 45
        assertEquals(List.of(45), hits().stream().map(CheatFinding::score).toList());
        assertTrue(Files.isRegularFile(ruleDir.resolve(RULE_FILE)), "应留下缓存文件");
        assertTrue(Files.isRegularFile(ruleDir.resolve(STATE_FILE)), "应记录已安装版本");
        assertEquals(SOURCE_V1, cacheOf(RULE), "缓存内容应是服务端下发的源码");
    }

    @Test
    void 版本与摘要未变时不重复下载() {
        assertEquals(1, sync());
        int downloads = fileHits.get();

        assertEquals(0, sync(), "版本与摘要都未变时不应再更新");
        assertEquals(downloads, fileHits.get(), "未变化时不应再下载源码");
        assertEquals(2, manifestHits.get(), "清单每轮都应拉取（服务端决定下发哪个版本）");
    }

    @Test
    void 版本号变化触发重新下载() {
        assertEquals(1, sync());

        version = "2.0.0";
        served = SOURCE_V2;
        servedSha = CodeIntegrityService.sha256Hex(bytes(SOURCE_V2));

        assertEquals(1, sync(), "版本号变化应触发下载");
        assertEquals(2, fileHits.get());
    }

    /** 记录说已安装、缓存文件却被清理过：必须重新下载，否则重启后会静默退回内置版本。 */
    @Test
    void 记录在但缓存文件缺失时重新下载() throws IOException {
        assertEquals(1, sync());
        Files.delete(ruleDir.resolve(RULE_FILE));

        assertEquals(1, sync(), "缓存文件缺失时应重新下载");
    }

    // ------------------------------------------------------------------ 降级

    @Test
    void 摘要不符时既不加载也不覆盖缓存() {
        assertEquals(1, sync());
        String goodCache = cacheOf(RULE);
        int goodScore = hits().get(0).score();

        version = "2.0.0";
        served = SOURCE_V2 + "\n# 被篡改\n";
        // servedSha 保持指向原内容，服务端报的摘要与实际内容对不上

        assertEquals(0, sync(), "摘要不符不得计入更新");
        assertEquals(goodScore, hits().get(0).score(), "引擎里必须还是旧规则");
        assertEquals(goodCache, cacheOf(RULE), "缓存不得被未通过校验的内容覆盖");
    }

    @Test
    void 编译失败时保留旧规则与旧缓存() {
        assertEquals(1, sync());
        String goodCache = cacheOf(RULE);
        int goodScore = hits().get(0).score();

        version = "2.0.0";
        served = "rule \"fasteat\" { when: 1 end";
        servedSha = CodeIntegrityService.sha256Hex(bytes(served));

        assertEquals(0, sync(), "编译不过的源码不得计入更新");
        assertEquals(goodScore, hits().get(0).score(), "编译失败后引擎里必须还是旧规则");
        assertEquals(goodCache, cacheOf(RULE), "编译失败不得污染缓存（校验→编译→落盘的顺序在这里体现）");
    }

    @Test
    void 网络不可用时安静降级() {
        RuleSync offline = new RuleSync("http://127.0.0.1:1", "token", engine, ruleDir);

        assertEquals(0, offline.syncOnce());
        assertFalse(Files.exists(ruleDir.resolve(STATE_FILE)), "失败不应留下记录");
        assertFalse(Files.exists(ruleDir.resolve(RULE_FILE)), "失败不应留下缓存");
    }

    @Test
    void 清单里没有规则时不做任何事() throws IOException {
        server.removeContext("/api/player/rules/manifest");
        server.createContext("/api/player/rules/manifest",
                ex -> respond(ex, 200, "{\"rules\":[]}".getBytes(StandardCharsets.UTF_8)));

        assertEquals(0, sync());
        assertEquals(0, fileHits.get());
        assertFalse(Files.exists(ruleDir.resolve(STATE_FILE)));
    }

    // ------------------------------------------------------------------ 缓存装载

    @Test
    void 重启后从缓存重新装载规则() throws IOException {
        assertEquals(1, sync());
        // 模拟重启：换一个引擎，只有内置规则
        PrlDetectionEngine restarted = new PrlDetectionEngine();
        restarted.loadBuiltin();
        assertEquals(0, topScore(restarted), "前置条件：重启后先回到内置版本，这一输入打不动它");

        RuleSync afterRestart = new RuleSync(baseUrl(), "token", restarted, ruleDir);

        assertEquals(1, afterRestart.loadCache(), "缓存里的规则应被重新装载");
        assertEquals(45, topScore(restarted), "装载后应当是在用的下发版本");
    }

    /** 单条缓存损坏只跳过它自己，不能让整批下发规则失效。 */
    @Test
    void 缓存里有一条损坏时其余规则照常装载() throws IOException {
        Files.createDirectories(ruleDir);
        Files.writeString(ruleDir.resolve(RULE_FILE), SOURCE_V2, StandardCharsets.UTF_8);
        Files.writeString(ruleDir.resolve("fastplace.prl"), "rule \"fastplace\" { when: 1", StandardCharsets.UTF_8);
        Files.writeString(ruleDir.resolve(STATE_FILE),
                "{\"fasteat.version\":\"2.0.0\",\"fasteat.sha256\":\"x\","
                        + "\"fastplace.version\":\"2.0.0\",\"fastplace.sha256\":\"y\"}",
                StandardCharsets.UTF_8);

        assertEquals(1, loadCacheOnly(), "损坏的那条被跳过，另一条仍要装载");
        assertEquals(70, topScore(engine));
    }

    @Test
    void 记录里没有版本键时视为未安装() throws IOException {
        Files.createDirectories(ruleDir);
        Files.writeString(ruleDir.resolve(RULE_FILE), SOURCE_V2, StandardCharsets.UTF_8);
        // 只有 sha256 键、没有 version 键：不是可装载的记录
        Files.writeString(ruleDir.resolve(STATE_FILE),
                "{\"fasteat.sha256\":\"x\"}", StandardCharsets.UTF_8);

        assertEquals(0, loadCacheOnly());
    }

    /** 规则名即作弊类型 code，认不出的名字在引擎层就被拒绝，不会留下任何缓存。 */
    @Test
    void 清单里的未知规则名不落盘() throws IOException {
        server.removeContext("/api/player/rules/manifest");
        server.createContext("/api/player/rules/manifest", ex -> respond(ex, 200,
                ("{\"rules\":[{\"name\":\"../../escape\",\"version\":\"1.0.0\","
                        + "\"sha256\":\"" + CodeIntegrityService.sha256Hex(bytes(SOURCE_V2)) + "\","
                        + "\"status\":\"active\",\"url\":\"/api/player/rules/x/file\"}]}")
                        .getBytes(StandardCharsets.UTF_8)));
        server.createContext("/api/player/rules/x/file", ex -> respond(ex, 200, bytes(SOURCE_V2)));

        assertEquals(0, sync(), "未知规则名不得计入更新");
        assertFalse(Files.exists(ruleDir.resolve(".._.._escape.prl")));
        try (var files = Files.list(ruleDir)) {
            assertEquals(0, files.filter(p -> p.toString().endsWith(".prl")).count(),
                    "不该留下任何规则缓存");
        }
    }

    /**
     * 已安装记录是磁盘上的文件，玩家本机可以改。记录里的规则名必须收紧成字符白名单，
     * 否则一条 {@code ../secret} 就能让端侧去读缓存目录之外的任意 {@code .prl}。
     */
    @Test
    void 记录里的规则名不能读到缓存目录之外() throws IOException {
        Path cacheDir = ruleDir.resolve("nested/rules");
        Files.createDirectories(cacheDir);
        // 缓存目录外放一份合法规则源码，其落点正好是穿越路径要指向的地方
        Files.writeString(ruleDir.resolve("nested/secret.prl"), SOURCE_V2, StandardCharsets.UTF_8);
        Files.writeString(cacheDir.resolve(STATE_FILE),
                "{\"../secret.version\":\"2.0.0\"}", StandardCharsets.UTF_8);

        int applied = new RuleSync(baseUrl(), "token", engine, cacheDir).loadCache();

        assertEquals(0, applied, "穿越路径不得读出缓存目录以外的文件");
        assertEquals(0, topScore(engine), "引擎里不该出现目录外那份规则");
    }

    // ------------------------------------------------------------------ 辅助

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 用本测试的引擎同步一轮。 */
    private int sync() {
        return new RuleSync(baseUrl(), "demo-token", engine, ruleDir).syncOnce();
    }

    private int loadCacheOnly() {
        return new RuleSync(baseUrl(), "demo-token", engine, ruleDir).loadCache();
    }

    private String cacheOf(String name) {
        try {
            return Files.readString(ruleDir.resolve(name + ".prl"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private List<CheatFinding> hits() {
        return engine.evaluate(new FeatureVector(), new AnalysisContext(null, null, 0.5, 0.0, List.of()));
    }

    private static int topScore(PrlDetectionEngine target) {
        return target.evaluate(new FeatureVector(), new AnalysisContext(null, null, 0.5, 0.0, List.of()))
                .stream().filter(f -> f.type() == CheatType.FASTEAT)
                .mapToInt(CheatFinding::score).findFirst().orElse(0);
    }

    /** 只看 {@code temporal_anomaly} 的 fasteat 规则：分数随版本变化，用来区分「在跑哪个版本」。 */
    private static String source(String version, double score) {
        return """
                rule "fasteat" {
                    version: "%s"
                    severity: medium
                    input {
                        features: map[string, float]
                        temporal_anomaly: float
                        click_highly_likely: bool
                    }
                    when:
                        temporal_anomaly > 0.0
                    then:
                        emit_alert(type = "fasteat", confidence = 0.7, evidence = {
                            "score": %s,
                            "keys": []
                        })
                }
                """.formatted(version, score);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}