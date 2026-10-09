package com.potatotv.paccclient.plugin;

import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.spi.EventListener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内检测事件总线（文档 §2.2 {@code subscribe}/{@code emit}）。
 *
 * <p>按 {@code eventType} 精确订阅，{@value #WILDCARD} 表示订阅全部。单个监听器抛异常被就地吞掉并
 * 记日志，绝不波及其它订阅者 —— 一个坏插件不能让别人收不到事件。</p>
 */
public final class EventBus {

    /** 订阅全部事件的通配类型。 */
    public static final String WILDCARD = "*";

    private final Map<String, List<EventListener>> listeners = new ConcurrentHashMap<>();

    /** 订阅指定类型；{@code eventType} 为 {@code *} 时订阅全部。 */
    public void subscribe(String eventType, EventListener listener) {
        if (eventType == null || listener == null) {
            return;
        }
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** 发布一条事件，投递给精确订阅者与通配订阅者。 */
    public void publish(String eventType, DetectionEvent event) {
        if (eventType == null || event == null) {
            return;
        }
        dispatch(eventType, event);
        if (!WILDCARD.equals(eventType)) {
            dispatch(WILDCARD, event);
        }
    }

    private void dispatch(String key, DetectionEvent event) {
        List<EventListener> subs = listeners.get(key);
        if (subs == null) {
            return;
        }
        for (EventListener sub : subs) {
            try {
                sub.onEvent(event);
            } catch (RuntimeException e) {
                System.err.println("[PTV-Plugin] 事件监听器异常 type=" + key + ": " + e);
            }
        }
    }

    /** 清空全部订阅（卸载插件时用）。 */
    public void clear() {
        listeners.clear();
    }
}