package com.potatotv.prl.ast;

/**
 * 带单位字面量的单位族（设计文档 §2.2.3「时间/数据量」）。
 *
 * <p>三族在运行时的落点不同：</p>
 * <ul>
 *   <li>{@link #TIME} 归一化为毫秒（{@code long}），与宿主 {@code Duration} 参数对齐；</li>
 *   <li>{@link #DATA} 归一化为字节数（{@code long}）；</li>
 *   <li>{@link #RATE} 归一化为「每秒次数」（{@code double}），这样
 *       {@code 5 per_second} 与 {@code rate(events, 1s)} 的返回值可以直接比大小，
 *       {@code per_minute} 按其 1/60 折算。</li>
 * </ul>
 */
public enum UnitKind {

    TIME,
    DATA,
    RATE
}