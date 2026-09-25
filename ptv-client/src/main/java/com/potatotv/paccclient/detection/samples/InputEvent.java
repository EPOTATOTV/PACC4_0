package com.potatotv.paccclient.detection.samples;

/**
 * 原始输入事件（由鼠标/键盘 Hook 推入 {@code BufferedInputSource}）。
 *
 * @param timestampMillis 事件时间戳（毫秒）
 * @param kind            事件种类
 * @param holdMillis      按住时长（仅 {@link Kind#CLICK} 有意义，其余记 0）
 */
public record InputEvent(long timestampMillis, Kind kind, double holdMillis) {

    /** 输入事件种类：点击 / 挥臂 / 按键。 */
    public enum Kind { CLICK, SWING, KEY }

    /** 一次瞬时点击。 */
    public static InputEvent click(long timestampMillis) {
        return new InputEvent(timestampMillis, Kind.CLICK, 0.0);
    }

    /** 一次带按住时长的点击。 */
    public static InputEvent click(long timestampMillis, double holdMillis) {
        return new InputEvent(timestampMillis, Kind.CLICK, holdMillis);
    }

    /** 一次挥臂动画。 */
    public static InputEvent swing(long timestampMillis) {
        return new InputEvent(timestampMillis, Kind.SWING, 0.0);
    }
}