package com.potatotv.pacc.service.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.pacc.df.DfProperties;
import com.potatotv.pacc.domain.FeatureVector;
import com.potatotv.pacc.domain.plugin.DetectionContext;
import com.potatotv.pacc.domain.plugin.DetectionPlugin;
import com.potatotv.pacc.domain.plugin.DetectionResult;
import com.potatotv.pacc.domain.plugin.PluginMetadata;
import com.potatotv.pacc.domain.plugin.PluginRuntime;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 插件沙箱的超时中断与元数据缓存单测。
 *
 * <p>沙箱能不能兜住坏插件，取决于「超时之后有没有真的把插件线程掐掉」：
 * 只从 {@code Future.get()} 上抛超时而不 cancel，挂死的插件会一直占着线程池，
 * 连它加载过的 ClassLoader 一起被钉住不放。这类泄漏只在插件行为异常时暴露，
 * 得靠回归测试钉死。</p>
 */
class PluginSandboxTest {

    private static final PluginMetadata CACHED_METADATA =
            new PluginMetadata("cached", "Cached Plugin", "1.0.0", "test", null, Set.of("log"));

    private static PluginSandbox sandboxWithTimeout(long timeoutMs) {
        DfProperties props = new DfProperties();
        props.getPlugin().setTimeoutMs(timeoutMs);
        return new PluginSandbox(props);
    }

    @Test
    void metadataTimeoutInterruptsRunningTaskAndFallsBackToUnknown() throws InterruptedException {
        PluginSandbox sandbox = sandboxWithTimeout(120);
        CountDownLatch interrupted = new CountDownLatch(1);
        DetectionPlugin blocker = new StubPlugin() {
            @Override
            public PluginMetadata getMetadata() {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return CACHED_METADATA;
            }
        };
        PluginRuntime runtime = PluginRuntime.builder().pluginId("blocker").build();

        PluginMetadata meta = sandbox.metadata(blocker, runtime);

        assertEquals("unknown", meta.pluginId(), "元数据取不到时应退化为占位");
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "超时后应中断仍在跑的插件线程");
        assertEquals(1, runtime.getErrorCount());
    }

    @Test
    void suppliedMetadataIsNotRefetched() {
        PluginSandbox sandbox = sandboxWithTimeout(200);
        AtomicInteger metadataCalls = new AtomicInteger();
        DetectionPlugin plugin = new StubPlugin() {
            @Override
            public PluginMetadata getMetadata() {
                metadataCalls.incrementAndGet();
                return CACHED_METADATA;
            }
        };
        PluginRuntime runtime = PluginRuntime.builder().pluginId("cached").build();
        DetectionContext ctx = new DetectionContext(null, "player-1", "java", "tenant-1");

        DetectionResult result = sandbox.execute(plugin, ctx, runtime, CACHED_METADATA);

        assertFalse(result.isDetected());
        assertEquals(0, metadataCalls.get(), "调用方给了元数据就不应再取一次");
    }

    /** 测试用插件骨架：detect 一律返回干净结果，其余方法按用例覆写。 */
    private abstract static class StubPlugin implements DetectionPlugin {

        @Override
        public void initialize() {
            // 本用例不涉及初始化
        }

        @Override
        public DetectionResult detect(FeatureVector vector) {
            return DetectionResult.clean();
        }

        @Override
        public void destroy() {
            // 无资源需要释放
        }
    }
}