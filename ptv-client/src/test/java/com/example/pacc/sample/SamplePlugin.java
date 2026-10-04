package com.example.pacc.sample;

import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.DetectionPlugin;
import com.potatotv.paccclient.spi.Detector;
import com.potatotv.paccclient.spi.FeatureCollectContext;
import com.potatotv.paccclient.spi.FeatureDim;
import com.potatotv.paccclient.spi.FeatureProvider;
import com.potatotv.paccclient.spi.PluginContext;
import com.potatotv.paccclient.spi.PluginMetadata;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 测试用插件入口类（放在 {@code com.example.*}，避开宿主类加载白名单）。
 *
 * <p>加载流程测试用的 JAR 只含 {@code META-INF/pacc-plugin.json}，入口类由此测试源提供；
 * {@code PluginClassLoader} 在 JAR 里找不到该类时会回退到父加载器（测试类路径）。</p>
 */
public final class SamplePlugin implements DetectionPlugin {

    public static volatile boolean loaded;
    public static volatile boolean unloaded;
    public static volatile String lastRule;

    public static void reset() {
        loaded = false;
        unloaded = false;
        lastRule = null;
    }

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata("com.example.sample", "Sample", "1.0.0", "tester",
                "test plugin", 1, 0);
    }

    @Override
    public void onLoad(PluginContext context) {
        loaded = true;
        context.logger().info("sample plugin loaded");
        context.registerDetector(new Detector() {
            @Override
            public String id() {
                return "sample_detector";
            }

            @Override
            public long intervalMs() {
                return 1000;
            }

            @Override
            public Optional<DetectionEvent> detect(DetectContext ctx) {
                return Optional.empty();
            }
        });
        context.registerFeatureProvider(new FeatureProvider() {
            @Override
            public String prefix() {
                return "ext_sample_";
            }

            @Override
            public List<FeatureDim> dimensions() {
                return List.of(new FeatureDim("ext_sample_flag", "sample flag", 0, 1));
            }

            @Override
            public Map<String, Double> collect(FeatureCollectContext ctx) {
                return Map.of("ext_sample_flag", 1.0);
            }
        });
        context.registerRule("speed", "(rule speed)");
        lastRule = "speed";
    }

    @Override
    public void onUnload() {
        unloaded = true;
    }
}