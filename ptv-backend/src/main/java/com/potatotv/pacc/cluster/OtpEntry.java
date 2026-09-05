package com.potatotv.pacc.cluster;

/** 验证码条目（只存哈希与过期/锁定元数据，不存明文）。 */
public record OtpEntry(long createdMs, int attempts, long failLockedMs, String codeHash) {
}