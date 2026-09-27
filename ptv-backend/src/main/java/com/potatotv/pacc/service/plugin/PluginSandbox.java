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
import java.util.concurrent.Callable;
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

    /**
     * 元数据取不到时的占位：插件标识、展示名失去来源，声明清单按空处理。
     *
     * <p>白名单只约束「已声明」的特权 API——声明为空表示该插件没有申请特权 API，
     * 属于合法状态、不会被拦下；兜住坏插件的是 detect 的超时与错误记账，不是这里的空声明。</p>
     */
    private static final PluginMetadata UNKNOWN_METADATA =
            new PluginMetadata("unknown", "unknown", "0.0.0", "unknown", null, null);

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
        return execute(plugin, ctx, runtime, metadata(plugin, runtime));
    }

    /**
     * 同上，但元数据由调用方给定。
     *
     * <p>已加载插件在加载期已取过一次元数据并缓存，每次 detect 再往沙箱线程池跑一趟
     * 只是白白多一次调度，因此由 {@code PluginManager} 把缓存直接传进来。</p>
     *
     * @param meta 加载期缓存的插件元数据（非空）
     */
    public DetectionResult execute(DetectionPlugin plugin, DetectionContext ctx, PluginRuntime runtime,
                                   PluginMetadata meta) {
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
            cancelQuietly(future);
            bumpError(runtime);
            result = DetectionResult.failure("timeout");
        } catch (ExecutionException e) {
            bumpError(runtime);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("插件执行异常 plugin={} err={}", meta.pluginId(), cause.toString());
            result = DetectionResult.failure(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (InterruptedException e) {
            cancelQuietly(future);
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

    /**
     * 读取插件元数据，与 detect 一样受超时 / CPU 记账约束。
     *
     * <p>元数据决定后续的 API 白名单判定，属于安全判定输入，不能放在调用方线程裸奔：
     * 插件完全可以在 {@code getMetadata()} 里长时间阻塞或抛异常，
     * 那样沙箱只包住 detect 就等于边界形同虚设。失败时退化为「未知元数据」占位：
     * 声明清单为空等于「未申请特权 API」，按白名单语义照常放行，
     * 但这次失败已经计入错误数，坏插件会在运行时登记里留痕。</p>
     */
    public PluginMetadata metadata(DetectionPlugin plugin, PluginRuntime runtime) {
        try {
            PluginMetadata m = guarded(runtime, "getMetadata", plugin::getMetadata);
            return m == null ? UNKNOWN_METADATA : m;
        } catch (RuntimeException e) {
            return UNKNOWN_METADATA;
        }
    }

    /**
     * 在沙箱约束内执行插件初始化。
     *
     * <p>生命周期入口（initialize）与 detect 同等对待：超时、CPU 记账、异常隔离一个不少。
     * 失败向上抛，由 {@code PluginManager} 记为 ERROR 并让加载整体失败——
     * 初始化失败的插件不应以「已加载」的状态留在运行时。</p>
     */
    public void initialize(DetectionPlugin plugin, PluginRuntime runtime) {
        guarded(runtime, "initialize", () -> {
            plugin.initialize();
            return null;
        });
    }

    /** 沙箱内受约束调用：超时中断 + CPU 记账 + 异常归一，无论成败都计入 CPU。 */
    private <T> T guarded(PluginRuntime runtime, String stage, Callable<T> body) {
        long start = System.nanoTime();
        Future<T> future = null;
        try {
            future = EXECUTOR.submit(body);
            return future.get(props.getPlugin().getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancelQuietly(future);
            bumpError(runtime);
            throw new IllegalStateException("插件 " + stage + " 超时(" + props.getPlugin().getTimeoutMs() + "ms)");
        } catch (ExecutionException e) {
            bumpError(runtime);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("插件调用异常 stage={} err={}", stage, cause.toString());
            throw new IllegalStateException("插件 " + stage + " 异常: " + cause.getClass().getSimpleName(), cause);
        } catch (InterruptedException e) {
            cancelQuietly(future);
            Thread.currentThread().interrupt();
            bumpError(runtime);
            throw new IllegalStateException("插件 " + stage + " 被中断", e);
        } catch (RuntimeException e) {
            // 提交/取值阶段的意外异常同样计入并上抛
            bumpError(runtime);
            throw e;
        } finally {
            runtime.setCpuMs(runtime.getCpuMs() + (System.nanoTime() - start) / 1_000_000L);
            runtime.setUpdatedAt(java.time.Instant.now());
        }
    }

    /**
     * 中断仍在跑的任务（Future 可能尚未建立，故容 null）。
     *
     * <p>超时或中断后不 cancel，挂死的插件线程会永久占住池子，
     * 连它加载过的 ClassLoader 也一并被钉住不放——反复失败就是持续泄漏。</p>
     */
    private static void cancelQuietly(Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    private static void bumpError(PluginRuntime runtime) {
        runtime.setErrorCount(runtime.getErrorCount() + 1);
    }
}