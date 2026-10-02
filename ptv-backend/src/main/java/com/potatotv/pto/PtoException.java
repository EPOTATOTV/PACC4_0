package com.potatotv.pto;

/**
 * PTO 令牌签发/校验失败。
 * <p>任何校验失败（格式、签名、issuer、时间窗）都以本异常抛出；调用方一律视为「令牌不可信」，
 * 对外只应给出通用文案，不要把具体失败原因回给客户端。</p>
 */
public class PtoException extends RuntimeException {

    public PtoException(String message) {
        super(message);
    }

    public PtoException(String message, Throwable cause) {
        super(message, cause);
    }
}
