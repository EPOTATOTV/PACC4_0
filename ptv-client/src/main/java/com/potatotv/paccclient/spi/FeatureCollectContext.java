package com.potatotv.paccclient.spi;

import com.potatotv.paccclient.probe.OsInfo;
import com.potatotv.paccclient.probe.SystemProbe;

import java.util.Objects;

/**
 * 特征采集上下文（文档 §2.2 {@code FeatureProvider.collect}）。
 *
 * <p>只暴露只读系统探针与系统摘要，插件据此采集扩展特征；采集本身不得读取文件内容或注册表键值
 * （与 {@link SystemProbe} 的约定一致），也不得发起网络请求。</p>
 */
public record FeatureCollectContext(SystemProbe systemProbe) {

    public FeatureCollectContext {
        Objects.requireNonNull(systemProbe, "systemProbe");
    }

    /** 系统信息摘要（脱敏）。 */
    public OsInfo osInfo() {
        return systemProbe.osInfo();
    }
}