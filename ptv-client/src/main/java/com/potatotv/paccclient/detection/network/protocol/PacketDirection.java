package com.potatotv.paccclient.detection.network.protocol;

/**
 * 数据包方向（网络代理层 §2.4）。
 *
 * <p>{@link #C2S} 客户端 → 服务端（上行），{@link #S2C} 服务端 → 客户端（下行）。
 * 代理只做旁路留痕，方向仅用于区分字节流来源、喂给对应解析器，不改变转发内容。</p>
 */
public enum PacketDirection {
    /** 客户端 → 服务端。 */
    C2S,
    /** 服务端 → 客户端。 */
    S2C
}