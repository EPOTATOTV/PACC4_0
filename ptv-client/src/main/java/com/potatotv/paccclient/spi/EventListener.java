package com.potatotv.paccclient.spi;

import com.potatotv.paccclient.detection.DetectionEvent;

/**
 * 事件订阅回调（文档 §2.2 {@code PluginContext.subscribe}）。
 * 宿主保证回调在插件调用线程之外执行，且单个监听器抛异常不会影响其它订阅者与事件总线本身。
 */
@FunctionalInterface
public interface EventListener {

    void onEvent(DetectionEvent event);
}