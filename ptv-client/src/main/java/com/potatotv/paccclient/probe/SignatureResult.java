package com.potatotv.paccclient.probe;

/**
 * 单个可执行文件 / 模块的数字签名验证结果（文档 §4.2 / §4.5）。
 *
 * @param path      文件路径（不可得置空串）
 * @param valid     签名是否有效（无签名 / 签名损坏均为 false）
 * @param publisher 签名发布者（不可得时为 null；未签名时也为 null）
 * @param note      说明（探针降级原因等，可为 null）
 */
public record SignatureResult(String path, boolean valid, String publisher, String note) {

    /** 未签名或验证失败的结果（无发布者）。 */
    public static SignatureResult invalid(String path) {
        return new SignatureResult(path, false, null, null);
    }
}