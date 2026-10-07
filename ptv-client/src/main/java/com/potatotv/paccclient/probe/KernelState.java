package com.potatotv.paccclient.probe;

/**
 * 内核级回调检测结果（文档 §4.4）。
 *
 * <p><b>能力边界</b>：SSDT / IDT hook 与内核回调枚举需要在 Ring0 读取内核结构，
 * 用户态无法可靠完成（文档 §7 风险表「内核回调检测需要驱动 → 第一版只做用户态检测；
 * 内核检测作为可选模块」）。因此本批次把内核字段留作「能力占位」：驱动签名验证这类
 * 用户态可得的结论照常返回，SSDT / IDT / 回调计数在无内核驱动配合时保持 0 且
 * {@link #supported()} 为 false，不做任何伪造。</p>
 *
 * @param supported                 是否有内核级能力支撑（用户态版本恒 false）
 * @param ssdtHooks                 SSDT hook 数
 * @param idtHooks                  IDT hook 数
 * @param unsignedDriverCount       未签名驱动数（用户态可经驱动签名验证得到）
 * @param suspiciousCallbackCount   可疑内核回调数
 * @param note                      能力说明（可为 null）
 */
public record KernelState(boolean supported, int ssdtHooks, int idtHooks,
                          int unsignedDriverCount, int suspiciousCallbackCount, String note) {

    /** 无内核能力：全部计数为 0 并附原因。 */
    public static KernelState unsupported(String note) {
        return new KernelState(false, 0, 0, 0, 0, note);
    }
}