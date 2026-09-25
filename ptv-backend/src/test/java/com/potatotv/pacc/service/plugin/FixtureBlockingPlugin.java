package com.potatotv.pacc.service.plugin;

import com.potatotv.pacc.domain.FeatureVector;
import com.potatotv.pacc.domain.plugin.DetectionPlugin;
import com.potatotv.pacc.domain.plugin.DetectionResult;
import com.potatotv.pacc.domain.plugin.PluginMetadata;

import java.util.Set;

/**
 * 加载失败路径单测用的插件：{@code initialize()} 会阻塞到被沙箱掐断。
 *
 * <p>它本身不参与任何业务，只用于验证「初始化超时导致加载失败时，沙箱的 CPU 与错误记账
 * 仍会落到运行时登记」：测试把它的字节码按包结构复制进临时插件目录后交给
 * {@code PluginManager} 加载。</p>
 */
public class FixtureBlockingPlugin implements DetectionPlugin {

    @Override
    public void initialize() {
        try {
            Thread.sleep(30_000);
        } catch (InterruptedException e) {
            // 沙箱超时会 cancel 掉这个任务，这里只要配合退出，别把中断标志吞掉
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public DetectionResult detect(FeatureVector vector) {
        return DetectionResult.clean();
    }

    @Override
    public PluginMetadata getMetadata() {
        return new PluginMetadata("blocking", "Blocking Plugin", "1.0.0", "test", null, Set.of());
    }

    @Override
    public void destroy() {
        // 无资源需要释放
    }
}