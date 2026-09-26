package com.potatotv.pcu;

import java.util.Locale;

/**
 * 更新通道（设计文档 §4.8）：决定端侧从哪个通道取版本。
 *
 * <p>通道由客户端配置决定，服务端按 {@code platform × channel} 查已发布版本。
 * 未识别的通道名一律回落到 {@link #STABLE}，避免服务端多出一个通道就把端侧更新链路打断。</p>
 */
public enum UpdateChannel {

    /** 稳定版，经过完整测试。 */
    STABLE("stable"),
    /** 测试版，新功能先体验。 */
    BETA("beta"),
    /** 开发版，可能不稳定，内部测试/开发者。 */
    ALPHA("alpha"),
    /** 开发版，每次 commit 构建，仅 PTV 内部。 */
    DEV("dev");

    private final String wire;

    UpdateChannel(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 解析通道名；未知或空值回落到 stable。 */
    public static UpdateChannel fromWire(String text) {
        if (text == null || text.isBlank()) {
            return STABLE;
        }
        String low = text.trim().toLowerCase(Locale.ROOT);
        for (UpdateChannel c : values()) {
            if (c.wire.equals(low)) {
                return c;
            }
        }
        return STABLE;
    }
}