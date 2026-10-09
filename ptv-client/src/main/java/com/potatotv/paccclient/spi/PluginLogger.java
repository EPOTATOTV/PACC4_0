package com.potatotv.paccclient.spi;

/**
 * 插件日志接口（文档 §2.2）。宿主实现会给每条日志加上插件 ID 前缀，统一写入 PACC 日志，
 * 便于按插件归属排查问题。
 */
public interface PluginLogger {

    void info(String message);

    void warn(String message);

    void error(String message);

    default void error(String message, Throwable cause) {
        error(message + ": " + (cause == null ? "null" : cause.toString()));
    }
}