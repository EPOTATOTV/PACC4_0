package com.potatotv.pacc.service.detection.df.multimodal;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DF §4.1.3 图像模态评分器：屏幕截图 OCR → 透视 / 自瞄界面。
 *
 * <p>OCR 命中 ESP HUD、自瞄准星叠加层或外挂菜单文本属高置信证据；{@code ocr_confidence}
 * 与未知面板占比用于压制低置信误检。</p>
 */
@Component
public class ImageModalityScorer extends WeightedModalityScorer {

    private static final List<Signal> SIGNALS = List.of(
            new Signal("ocr_esp_hud", 1.0, Direction.HIGH_BAD),
            new Signal("ocr_aim_overlay", 1.0, Direction.HIGH_BAD),
            new Signal("ocr_cheat_menu", 1.0, Direction.HIGH_BAD),
            new Signal("ocr_confidence", 0.8, Direction.HIGH_BAD),
            new Signal("ocr_unknown_panel", 0.5, Direction.HIGH_BAD));

    @Override
    public Modality modality() {
        return Modality.IMAGE;
    }

    @Override
    protected List<Signal> signalSpecs() {
        return SIGNALS;
    }
}