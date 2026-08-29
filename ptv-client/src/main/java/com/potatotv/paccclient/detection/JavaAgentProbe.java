package com.potatotv.paccclient.detection;

import java.util.Optional;

/**
 * Java 版模糊环境检测：通过 -javaagent 注入到游戏进程，检测模块注入、
 * 内存 F3 篡改、客户端 Mod 特征。
 * <p>说明：真实实现位于 platform/java-agent；此处为调用协议骨架。</p>
 */
public final class JavaAgentProbe {

    /** 从收发两端都应识别 Java 版专属特征。 */
    public Optional<DetectionEvent> scanForMods(boolean suspiciousModuleLoaded) {
        if (suspiciousModuleLoaded) {
            return Optional.of(new DetectionEvent("java_mod", "critical", 96,
                    "javaw.exe", null, "injected-module", "java21"));
        }
        return Optional.empty();
    }
}