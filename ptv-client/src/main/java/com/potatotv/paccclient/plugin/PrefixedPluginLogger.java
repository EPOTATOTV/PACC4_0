package com.potatotv.paccclient.plugin;

import com.potatotv.paccclient.spi.PluginLogger;

/**
 * 带插件 ID 前缀的日志实现（文档 §2.2）：统一写入 PACC 标准输出/错误流，
 * 前缀形如 {@code [PTV-Plugin:net.potatotv.ce-detector]}，便于按插件归属过滤。
 */
public final class PrefixedPluginLogger implements PluginLogger {

    private final String prefix;

    public PrefixedPluginLogger(String pluginId) {
        this.prefix = "[PTV-Plugin:" + (pluginId == null ? "unknown" : pluginId) + "] ";
    }

    @Override
    public void info(String message) {
        System.out.println(prefix + message);
    }

    @Override
    public void warn(String message) {
        System.out.println(prefix + "[WARN] " + message);
    }

    @Override
    public void error(String message) {
        System.err.println(prefix + "[ERROR] " + message);
    }
}