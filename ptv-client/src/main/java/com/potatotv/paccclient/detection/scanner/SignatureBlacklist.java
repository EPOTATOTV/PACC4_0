package com.potatotv.paccclient.detection.scanner;

import java.util.List;
import java.util.Locale;

/**
 * 已知作弊软件发布者黑名单（文档 §4.2 / §4.5）。
 *
 * <p>模块签名检测（{@link DllSignatureScanner}）与未签名可执行文件检测
 * （{@link UnsignedExecutableScanner}）共用同一份发布者名单，避免两处维护漂移。
 * 匹配规则：发布者名称小写后做子串包含匹配，例如发布者 {@code "Horion"} 命中 {@code "horion"}。</p>
 */
public final class SignatureBlacklist {

    /** 小写发布者关键词；命中任一即为黑名单。 */
    private static final List<String> KEYWORDS = List.of(
            "horion", "zephyr", "codebreak", "32k", "cheat engine", "wemod", "fling");

    private SignatureBlacklist() {
    }

    /** 发布者是否为已知作弊软件作者 / 组织（{@code null} 或空串返回 false）。 */
    public static boolean isBlacklisted(String publisher) {
        if (publisher == null || publisher.isBlank()) {
            return false;
        }
        String lower = publisher.toLowerCase(Locale.ROOT);
        for (String keyword : KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}