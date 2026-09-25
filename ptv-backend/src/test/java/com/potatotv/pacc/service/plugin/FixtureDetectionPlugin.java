package com.potatotv.pacc.service.plugin;

import com.potatotv.pacc.domain.FeatureVector;
import com.potatotv.pacc.domain.plugin.DetectionPlugin;
import com.potatotv.pacc.domain.plugin.DetectionResult;
import com.potatotv.pacc.domain.plugin.PluginMetadata;

import java.util.Set;

/**
 * 插件加载边界单测用的最小插件实现。
 *
 * <p>它本身不参与任何业务，只用于被打包进「插件目录」后验证合法相对路径能被加载：
 * 测试会把这个类的字节码按包结构复制到临时插件目录，再由 {@code PluginManager} 加载。</p>
 */
public class FixtureDetectionPlugin implements DetectionPlugin {

    @Override
    public void initialize() {
        // 无资源需要初始化
    }

    @Override
    public DetectionResult detect(FeatureVector vector) {
        return DetectionResult.clean();
    }

    @Override
    public PluginMetadata getMetadata() {
        return new PluginMetadata("fixture", "Fixture Plugin", "1.0.0", "test", null, Set.of("log"));
    }

    @Override
    public void destroy() {
        // 无资源需要释放
    }
}