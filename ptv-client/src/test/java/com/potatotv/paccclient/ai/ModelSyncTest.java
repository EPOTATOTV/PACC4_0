package com.potatotv.paccclient.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * v5.2 §2.1.3 模型下发链路测试：清单驱动的下载 → 校验 → 安装 → 热加载，以及
 * 重复同步不重复下载、产物损坏时保留旧模型、网络不可用时安静降级。
 */
class ModelSyncTest {

    private static final int DIM = 4;

    @TempDir
    Path modelDir;

    private HttpServer server;
    private ModelRepository repository;
    private byte[] artifact;
    private volatile byte[] served;
    private volatile String version = "7";
    private String sha;

    private final AtomicInteger manifestHits = new AtomicInteger();
    private final AtomicInteger fileHits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        artifact = sampleModelBytes();
        sha = PaccModelFormat.sha256Hex(artifact);
        served = artifact;
        repository = new ModelRepository(modelDir);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/player/v52/model/manifest", ex -> {
            manifestHits.incrementAndGet();
            respond(ex, 200, ("{\"models\":[{\"model_type\":\"XGBOOST\",\"version\":\"" + version
                    + "\",\"sha256\":\"" + sha + "\",\"signature\":\"\","
                    + "\"url\":\"/api/player/v52/model/XGBOOST/file\"}]}").getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/api/player/v52/model/XGBOOST/file", ex -> {
            fileHits.incrementAndGet();
            respond(ex, 200, served);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void installsArtifactAndHotLoadsModel() {
        LocalAiModel ai = new LocalAiModel();
        assertFalse(ai.loaded(), "初始不应有模型");

        assertTrue(sync(ai), "首次同步应安装模型");
        assertTrue(ai.loaded(), "安装后应热加载进 LocalAiModel");
        assertTrue(ai.modelVersion() > 0);
        assertTrue(Files.isRegularFile(repository.modelFile(PaccModelFormat.TYPE_XGBOOST)));
        assertTrue(Files.isRegularFile(modelDir.resolve("installed-models.json")), "应记录已安装版本");
        assertNotNull(repository.loadCurrent(PaccModelFormat.TYPE_XGBOOST));
    }

    @Test
    void repeatSyncSkipsWhenVersionAndDigestUnchanged() {
        LocalAiModel ai = new LocalAiModel();
        assertTrue(sync(ai));
        int hits = fileHits.get();

        assertFalse(sync(ai), "版本与摘要未变时不应重复安装");
        assertEquals(hits, fileHits.get(), "未变化时不应再下载产物");
        assertEquals(2, manifestHits.get(), "清单每次都应拉取（服务端决定下发版本）");
    }

    @Test
    void versionBumpTriggersReinstall() {
        LocalAiModel ai = new LocalAiModel();
        assertTrue(sync(ai));

        version = "8";
        assertTrue(sync(ai), "版本号变化应触发重新下载");
        assertEquals(2, fileHits.get());
    }

    @Test
    void corruptedArtifactIsRejectedAndOldModelKept() {
        LocalAiModel ai = new LocalAiModel();
        assertTrue(sync(ai));
        byte[] good = repository.loadCurrent(PaccModelFormat.TYPE_XGBOOST);

        byte[] bad = artifact.clone();
        bad[bad.length - 1] ^= 0xFF;
        served = bad;
        version = "8";

        assertFalse(sync(ai), "摘要不一致的产物不得安装");
        assertFalse(ai.loaded() && ai.modelVersion() > 1, "不应切换到未通过校验的模型");
        assertNotNull(good);
        assertEquals(PaccModelFormat.sha256Hex(good),
                PaccModelFormat.sha256Hex(repository.loadCurrent(PaccModelFormat.TYPE_XGBOOST)),
                "旧模型应保持原样");
    }

    @Test
    void networkFailureDegradesQuietly() {
        LocalAiModel ai = new LocalAiModel();
        ModelSync offline = new ModelSync("http://127.0.0.1:1", "token", repository);

        assertFalse(offline.syncOnce(ai));
        assertFalse(ai.loaded());
        assertFalse(Files.exists(repository.modelFile(PaccModelFormat.TYPE_XGBOOST)));
    }

    private boolean sync(LocalAiModel ai) {
        int port = server.getAddress().getPort();
        return new ModelSync("http://127.0.0.1:" + port, "demo-token", repository).syncOnce(ai);
    }

    /** 训练一个微型 XGBoost 并封装成合法 .paccm 容器。 */
    private static byte[] sampleModelBytes() {
        int n = 40;
        double[][] x = new double[n][DIM];
        double[] y = new double[n];
        Random rng = new Random(52L);
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < DIM; d++) x[i][d] = rng.nextDouble();
            y[i] = x[i][0] > 0.5 ? 1.0 : 0.0;
        }
        XGBoostModel model = XGBoostModel.train(x, y, 5, 3, 0.5, 52L);
        byte[] weights = model.serialize();
        return PaccModelFormat.encode(new PaccModelFormat.ModelHeader(
                PaccModelFormat.FORMAT_VERSION, PaccModelFormat.TYPE_XGBOOST, DIM, weights.length), weights);
    }

    private static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}