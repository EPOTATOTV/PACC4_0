package com.potatotv.pacc.agent;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 字节码特征扫描器：真实解析 class 文件常量池（CONSTANT_Utf8），
 * 在其中检索作弊 / 注入特征签名，避免误伤正常类名。
 */
public final class SignatureScanner {

    /** 特征签名表（可按 PTV 灰度下发扩展）。signature = 特征码ID，pattern 为正则。 */
    private static final List<Rule> RULES = List.of(
            Rule.of("hack_client_pkg", Pattern.compile("(?i)(liquidbounce|meteor\\s?client|aristois|impactclient|sigma\\s?client|wurst|novoline|inertia|hackclient|fdp\\s?client|orbit\\s?client)")),
            Rule.of("killaura_class", Pattern.compile("(?i)(kill[ \\-]?aura|aimassist|aimbot\\b|reach[ \\-]?module)")),
            Rule.of("esp_loader", Pattern.compile("(?i)(esp\\s?module|wallhack\\b|tracers\\b|nametag\\s?(esp|module))")),
            Rule.of("bytecode_modifier", Pattern.compile("(?i)(javassist|redefineclasses|instrumentation\\s?redefine|asm\\s?classwriter)")),
            Rule.of("cheat_engine", Pattern.compile("(?i)(speedhack|cheatengine\\b|trace\\s?copier|dbvm|vmt\\s?hook)")),
            Rule.of("injection_agent", Pattern.compile("(?i)(signed\\s?payload|dll\\s?injection|process\\s?injector\\b)"))
    );

    private SignatureScanner() {
    }

    /**
     * 对给定 class 字节做常量池字符串提取并比对特征。
     *
     * @param className  形如 "com/foo/Bar"
     * @param bytes      原始 class 字节
     * @return 首个命中的规则（无命中返回空）
     */
    public static Optional<Rule> scan(String className, byte[] bytes) {
        if (bytes == null || bytes.length < 8) return Optional.empty();
        // class 文件魔数校验
        if (!(bytes[0] == (byte) 0xCA && bytes[1] == (byte) 0xFE
                && bytes[2] == (byte) 0xBA && bytes[3] == (byte) 0xBE)) return Optional.empty();

        // 常量池遍历
        int pos = 8;
        int cpCount = u2(bytes, pos);
        pos += 2;
        int scannerClassLen = className == null ? 0 : className.length() + 32;
        for (int i = 1; i < cpCount; i++) {
            if (pos + 1 > bytes.length) break;
            int tag = bytes[pos] & 0xFF;
            switch (tag) {
                case 1 -> { // CONSTANT_Utf8
                    int len = u2(bytes, pos + 1);
                    pos += 3;
                    if (pos + len > bytes.length) return Optional.empty();
                    String s = new String(bytes, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                    pos += len;
                    if (s.isEmpty()) continue;
                    boolean self = s.length() <= scannerClassLen
                            && className != null && (className.equals(s) || ("L" + s + ";").equals(className));
                    if (!self) {
                        for (Rule r : RULES) {
                            if (r.pattern.matcher(s).find()) {
                                return Optional.of(r);
                            }
                        }
                    }
                }
                case 3, 4 -> pos += 5;          // int / float（1 tag + 4 数据）
                case 5, 6 -> { pos += 9; i++; } // long / double 占两项（1 + 8）
                case 7, 8 -> pos += 3;          // Class / String（1 + 2 index）
                case 9, 10, 11, 12 -> pos += 5; // ref / NameAndType（1 + 4）
                case 15 -> pos += 4;            // MethodHandle（1 + 3）
                case 16 -> pos += 3;            // MethodType（1 + 2）
                case 17, 18 -> pos += 5;        // Dynamic/InvokeDynamic（1 + 4）
                case 19, 20 -> pos += 3;        // Module/Package（1 + 2）
                default -> { return Optional.empty(); }
            }
        }
        return Optional.empty();
    }

    private static int u2(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    /** 特征规则。可在 PTV 特征库中按此 signature 灰度管理。 */
    public record Rule(String signature, Pattern pattern) {
        static Rule of(String s, Pattern p) {
            return new Rule(s, p);
        }
    }
}