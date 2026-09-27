package com.potatotv.pcu;

import java.util.logging.Logger;

/**
 * 新版本健康检查（设计文档 §4.6.1）：更新后启动新版本，在超时窗口内探到「活着」才算成功，
 * 否则触发自动回滚。
 *
 * <p>探测方式由宿主决定——客户端可以打本地控制端口、可以看进程是否还在。PCU 只规定
 * 「在超时窗口内返回一次 true 就算健康」。</p>
 */
@FunctionalInterface
public interface HealthProbe {

    boolean healthy();

    /**
     * 宿主未提供探针时的占位实现：直接算健康，并明确告警。
     *
     * <p>之所以不在这里代为调用 {@link PlatformAdapter#startPacc()}——更新流程的应用阶段
     * 已经拉起过新版本了，再拉一次会多出一个进程。也就是说，没配探针时「启动失败自动回滚」
     * 只覆盖「启动命令直接失败」这一种情况；要覆盖「起来了但不可用」，宿主必须提供真实探针。</p>
     */
    static HealthProbe assumeHealthy() {
        Logger.getLogger(HealthProbe.class.getName()).warning(
                "未配置更新后健康探针，新版本启动后不做可用性校验（建议宿主提供真实探针）");
        return () -> true;
    }
}