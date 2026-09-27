package com.potatotv.pacc.service.detection.df.multimodal;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DF §4.1.3 内存模态评分器：游戏进程内存快照 → 内存修改 / 注入。
 *
 * <p>判定信号为写入事件、注入模块数、Hook 数、页守卫命中与未知内存区占比——任一显著升高都说明
 * 进程映像已被外部改写或注入。</p>
 */
@Component
public class MemoryModalityScorer extends WeightedModalityScorer {

    private static final List<Signal> SIGNALS = List.of(
            new Signal("memory_write_events", 5.0, Direction.HIGH_BAD),
            new Signal("injected_module_count", 1.0, Direction.HIGH_BAD),
            new Signal("hook_count", 3.0, Direction.HIGH_BAD),
            new Signal("page_guard_hits", 1.0, Direction.HIGH_BAD),
            new Signal("unknown_region_ratio", 0.3, Direction.HIGH_BAD));

    @Override
    public Modality modality() {
        return Modality.MEMORY;
    }

    @Override
    protected List<Signal> signalSpecs() {
        return SIGNALS;
    }
}