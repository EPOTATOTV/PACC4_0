package com.potatotv.paccclient.ai;

import java.io.IOException;

/**
 * {@code .paccm} 模型容器解析异常：魔数错误、长度不一致、加密封装被篡改、校验和不匹配等。
 * <p>继承 {@link IOException}，便于调用方按“模型不可用”统一降级到规则引擎（见文档 §2.1.4）。</p>
 */
public class ModelFormatException extends IOException {

    public ModelFormatException(String message) {
        super(message);
    }

    public ModelFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}