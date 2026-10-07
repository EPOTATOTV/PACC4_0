package com.potatotv.paccclient.detection.network.protocol;

/**
 * 游戏协议族（网络代理层 §2.3）。
 *
 * <p>{@link #JAVA} 走 TCP + 本地 SOCKS5 代理；{@link #BEDROCK} 走 UDP + RakNet 中继。
 * 两者的包 ID 空间、字节序（Java 大端 / 基岩小端）与帧结构都不同，由各自解析器处理。</p>
 */
public enum GameProtocol {
    /** Minecraft Java 版（TCP / SOCKS5）。 */
    JAVA,
    /** Minecraft 基岩版（UDP / RakNet 中继）。 */
    BEDROCK
}