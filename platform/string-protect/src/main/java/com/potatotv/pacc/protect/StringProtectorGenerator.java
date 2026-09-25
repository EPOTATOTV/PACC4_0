package com.potatotv.pacc.protect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 字符串加固的构建期生成器：把一份敏感字面量清单编译成「密文字节数组 + 解密方法」的 Java 类。
 *
 * <p>产出的类不出现任何明文，只留 {@code private static final byte[]} 常量与
 * {@code public static String sN()} 取值方法；解密委托给 {@link StringVault}。
 * 消费方（如 ptv-client）只需在源码里调用 {@code PaccSecretStrings.s0()} 取回端点/密钥键。</p>
 *
 * <p>典型用法（ptv-client 的 opt-in profile 就是这么调的）：</p>
 * <pre>
 *   java com.potatotv.pacc.protect.StringProtectorGenerator \
 *     &lt;输出.java路径&gt; &lt;包名&gt; &lt;类名&gt; &lt;key&gt; &lt;字面量1&gt; &lt;字面量2&gt; ...
 * </pre>
 */
public final class StringProtectorGenerator {

    /**
     * 默认密钥。非 0、非 0x??00 形态（低 8 位不为 0），避免对短字面量出现「密文 == 明文」。
     * 注意它编译进产物、可被提取——这就是「提高成本」而非「保密」的本意。
     */
    public static final int DEFAULT_KEY = 0x5AC3;

    private StringProtectorGenerator() {
    }

    /**
     * 生成类源码（不落盘）。
     *
     * @param packageName 生成类所在包（如 {@code com.potatotv.paccclient.protect}）
     * @param className   生成类名（如 {@code PaccSecretStrings}）
     * @param key         XOR 密钥
     * @param literals    敏感字面量清单（顺序即 {@code s0()/s1()...} 的下标）
     */
    public static String generate(String packageName, String className, int key, List<String> literals) {
        StringBuilder sb = new StringBuilder();
        sb.append("// 由 platform/string-protect 的 StringProtectorGenerator 生成，请勿手工编辑。\n");
        sb.append("// 逐索引键控 XOR：只提高静态检索成本，不提供机密性（见 StringVault 注释）。\n");
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import ").append(StringVault.class.getName()).append(";\n\n");
        sb.append("public final class ").append(className).append(" {\n\n");
        sb.append("    private ").append(className).append("() {\n    }\n\n");
        sb.append("    private static final int KEY = ").append(key).append(";\n\n");

        for (int i = 0; i < literals.size(); i++) {
            byte[] enc = StringVault.encrypt(literals.get(i), key);
            sb.append("    private static final byte[] S").append(i).append(" = {");
            for (int j = 0; j < enc.length; j++) {
                if (j > 0) {
                    sb.append(", ");
                }
                sb.append("(byte)0x").append(String.format("%02X", enc[j] & 0xFF));
            }
            sb.append("};\n");
        }
        if (!literals.isEmpty()) {
            sb.append('\n');
        }

        for (int i = 0; i < literals.size(); i++) {
            sb.append("    /** 第 ").append(i).append(" 条字面量的明文（运行时解密）。 */\n");
            sb.append("    public static String s").append(i).append("() {\n");
            sb.append("        return StringVault.decrypt(S").append(i).append(", KEY);\n");
            sb.append("    }\n\n");
        }

        sb.append("    /** 按生成顺序返回全部明文。 */\n");
        sb.append("    public static String[] all() {\n");
        sb.append("        return new String[] {");
        for (int i = 0; i < literals.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("s").append(i).append("()");
        }
        sb.append("};\n    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** 生成源码并写入 {@code outFile}（自动创建父目录，UTF-8）。 */
    public static void write(Path outFile, String packageName, String className, int key, List<String> literals)
            throws IOException {
        Path parent = outFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outFile, generate(packageName, className, key, literals), StandardCharsets.UTF_8);
    }

    /** 命令行入口，见类注释。参数不足时打印用法并以退出码 2 结束。 */
    public static void main(String[] args) throws IOException {
        if (args.length < 5) {
            System.err.println("用法: StringProtectorGenerator <outFile> <packageName> <className> <key> <literal> [<literal> ...]");
            System.exit(2);
        }
        Path outFile = Path.of(args[0]);
        List<String> literals = new ArrayList<>();
        for (int i = 4; i < args.length; i++) {
            literals.add(args[i]);
        }
        write(outFile, args[1], args[2], Integer.decode(args[3]), literals);
        System.out.println("已生成 " + outFile + "（" + literals.size() + " 条字面量）");
    }
}