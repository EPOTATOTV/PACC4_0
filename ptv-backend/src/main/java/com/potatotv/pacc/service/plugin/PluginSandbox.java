package com.potatotv.pacc.service.plugin;

import com.potatotv.pacc.domain.plugin.DetectionContext;
import com.potatotv.pacc.domain.plugin.DetectionPlugin;
import com.potatotv.pacc.domain.plugin.DetectionResult;
import com.potatotv.pacc.domain.plugin.PluginMetadata;
import com.potatotv.pacc.domain.plugin.PluginRuntime;
import com.potatotv.pacc.df.DfProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * §4.2.2 插件沙箱：对第三方检测插件施加资源限制与安全隔离。
 *
 * <ul>
 *   <li><b>资源限制</b>：单次 detect 超时 + 单插件 CPU 预算（窗口内累计），超限即隔离；</li>
 *   <li><b>输出限制</b>：证据体积上限，超限截断，防止 OOM / 刷库；</li>
 *   <li><b>API 白名单</b>：插件声明的特权 API 必须在配置白名单内，否则拒绝执行；</li>
 *   <li><b>异常隔离</b>：插件抛出的任何 {@link Throwable} 都被吞掉并转为
 *       {@link DetectionResult#failure(String)}，保证整条检测流水线不因单个坏插件中断。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("null") // 沙箱内 Future/流式取值存在 Eclipse JDT null 误报
public class PluginSandbox {

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "plugin-sandbox-" + THREAD_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private final DfProperties props;

    /**
     * 在白名单与资源预算内执行插件检测。
     *
     * @param plugin  待执行插件
     * @param ctx     受限上下文
     * @param runtime 运行时登记（累计 CPU / 错误计数，由调用方持久化）
     * @return 检测结果；任何异常/超限都转为失败占位结果
     */
    public DetectionResult execute(DetectionPlugin plugin, DetectionContext ctx, PluginRuntime runtime) {
        PluginMetadata meta = safeMetadata(plugin);
        // 1) API 白名单
        for (String api : meta.declaredApis()) {
            if (!props.getPlugin().getAllowedApis().contains(api)) {
                bumpError(runtime);
                return DetectionResult.failure("api-not-whitelisted:" + api);
            }
        }
        // 2) CPU 预算
        if (runtime.getCpuMs() > props.getPlugin().getMaxCpuMsPerWindow()) {
            bumpError(runtime);
            return DetectionResult.failure("cpu-budget-exceeded");
        }
        // 3) 超时执行 + 异常隔离
        long start = System.nanoTime();
        DetectionResult result;
        Future<DetectionResult> future = EXECUTOR.submit(() -> plugin.detect(ctx.getFeatureVector(), ctx));
        try {
            result = future.get(props.getPlugin().getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            bumpError(runtime);
            result = DetectionResult.failure("timeout");
        } catch (ExecutionException e) {
            bumpError(runtime);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("插件执行异常 plugin={} err={}", meta.pluginId(), cause.toString());
            result = DetectionResult.failure(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            bumpError(runtime);
            result = DetectionResult.failure("interrupted");
        } catch (RuntimeException e) {
            // 提交/取消阶段的意外异常同样隔离
            bumpError(runtime);
            result = DetectionResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            runtime.setCpuMs(runtime.getCpuMs() + (System.nanoTime() - start) / 1_000_000L);
            runtime.setUpdatedAt(java.time.Instant.now());
        }
        if (result == null) {
            bumpError(runtime);
            return DetectionResult.failure("null-result");
        }
        return capOutput(result);
    }

    /** 输出体积封顶：超限时仅保留截断说明，防止插件返回超大证据。 */
    private DetectionResult capOutput(DetectionResult result) {
        int max = props.getPlugin().getMaxOutputBytes();
        Map<String, Object> evidence = result.getEvidence();
        if (max <= 0 || evidence.isEmpty()) {
            return result;
        }
        String rendered = String.valueOf(evidence);
        if (rendered.getBytes(StandardCharsets.UTF_8).length <= max) {
            return result;
        }
        Map<String, Object> trimmed = new LinkedHashMap<>();
        trimmed.put("truncated", true);
        trimmed.put("original_size_bytes", rendered.getBytes(StandardCharsets.UTF_8).length);
        return result.isDetected()
                ? DetectionResult.hit(result.getCheatType(), result.getConfidence(), result.getRiskScore(), trimmed)
                : DetectionResult.clean();
    }

    private PluginMetadata safeMetadata(DetectionPlugin plugin) {
        try {
            PluginMetadata m = plugin.getMetadata();
            return m == null ? new PluginMetadata("unknown", "unknown", "0.0.0", "unknown", null, null) : m;
        } catch (RuntimeException e) {
            return new PluginMetadata("unknown", "unknown", "0.0.0", "unknown", null, null);
        }
    }

    private static void bumpError(PluginRuntime runtime) {
        runtime.setErrorCount(runtime.getErrorCount() + 1);
    }
}