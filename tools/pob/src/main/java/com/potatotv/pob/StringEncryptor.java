package com.potatotv.pob;

import com.potatotv.pob.runtime.StringVault;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 字符串加密：把 {@code ldc/ldc_w <String>} 换成 {@code ldc_w <int>; invokestatic PobVault.get(I)String}，
 * 并在产物里注入一个解密库类。
 *
 * <h2>安全判定（宁少勿错）</h2>
 * <p>一个字符串常量只有在下列条件全部成立时才加密：</p>
 * <ul>
 *   <li>该 {@code CONSTANT_String} 的 UTF-8 没有被任何非 String 的常量池条目引用
 *       （否则它可能是类名/描述符，改了就断链）；</li>
 *   <li>该 {@code CONSTANT_String} 的下标没有出现在任何「非 Code 方法体」的属性里，
 *       （包括字段 ConstantValue、注解元素值、BootstrapMethods 实参……凡是这类引用
 *       一定以 u2 形式出现在属性内容里，因此用「扫一遍属性字节」保守判定）；
 *       出现在这类位置的字符串无法用改字节码的方式替换，动了就会改坏字段初值 / 注解值；</li>
 *   <li>不是白名单串、长度足够、内容不像类名或描述符；</li>
 *   <li>它确实是某个 {@code ldc} 的实参，且其所在方法能被 {@link CodeRewriter} 安全重写。</li>
 * </ul>
 * <p>题目要求的「不加密 Class.forName 的类名字符串」由「不像类名/描述符」这一条覆盖。</p>
 *
 * <p>BLOB 是单个 UTF-8 常量，受 u2 的 65535 字节上限约束：接近上限时停止继续加密并告警，
 * 宁可少加密也不产出非法 class。</p>
 */
final class StringEncryptor {

    private static final int LDC = 0x12;
    private static final int LDC_W = 0x13;
    private static final int INVOKESTATIC = 0xb8;

    private final PobRules rules;
    private final String vaultClassName;
    private final Set<String> classNameForms;

    private final Map<String, Integer> indexByValue = new LinkedHashMap<>();
    private final List<String> values = new ArrayList<>();
    private long blobEstimate = 33; // saltHex(32) + 第一个分隔符
    private boolean truncated;

    StringEncryptor(String targetPackage, PobRules rules, Renamer renamer) {
        this.rules = rules;
        this.vaultClassName = targetPackage + '/' + VaultNames.STRING_VAULT_SIMPLE;
        this.classNameForms = collectClassNameForms(renamer, rules);
    }

    /**
     * 就地加密所有类里的候选字符串。
     *
     * @return 注入产物用的解密库类字节；没有任何字符串被加密时返回 null
     */
    byte[] encrypt(List<ClassFile> classes) throws IOException {
        for (ClassFile cf : classes) {
            encryptClass(cf);
        }
        if (values.isEmpty()) {
            return null;
        }
        if (truncated) {
            System.err.println("POB：字符串加密数据接近 BLOB 上限，剩余字符串未加密");
        }
        return buildVaultClass(buildBlob());
    }

    // ------------------------------------------------------------------
    // 单类处理
    // ------------------------------------------------------------------

    private void encryptClass(ClassFile cf) {
        Map<Integer, String> valueByStringCp = new HashMap<>();
        Map<String, Boolean> safeByValue = new HashMap<>();
        List<ClassFile.Cp> cp = cf.constantPool();
        Set<Integer> referenced = attributeReferences(cf);
        Set<Integer> nonStringUtf8 = utf8UsedByNonStringEntries(cp);
        for (int i = 1; i < cp.size(); i++) {
            ClassFile.Cp c = cp.get(i);
            if (c == null || c.tag != ClassFile.C_STRING) {
                continue;
            }
            String value = cf.utf8(c.a);
            valueByStringCp.put(i, value);
            boolean safe = usable(value) && !referenced.contains(i) && !nonStringUtf8.contains(c.a);
            safeByValue.merge(value, safe, (a, b) -> a && b);
        }
        if (valueByStringCp.isEmpty()) {
            return;
        }

        for (ClassFile.Member method : cf.methods()) {
            for (ClassFile.Attr attr : method.attributes) {
                if (!"Code".equals(cf.utf8(attr.nameIndex))) {
                    continue;
                }
                byte[] rewritten = rewriteMethod(cf, attr, valueByStringCp, safeByValue);
                if (rewritten != null) {
                    attr.info = rewritten;
                }
            }
        }
        // 清空明文的判据不能靠「改写调用报没报成功」来累计——那个累计永远是两侧同步的。
        // 直接回到改写后的方法体里数：只有类里已经没有任何 ldc 再指向该值，才敢把明文抹掉；
        // 只要有方法体读不出来（无法枚举它还剩哪些 ldc），就整体放弃清空，宁可留明文。
        Set<String> stillLoaded = stillLoadedValues(cf, valueByStringCp);
        if (stillLoaded == null) {
            return;
        }
        for (Map.Entry<String, Boolean> e : safeByValue.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue()) && !stillLoaded.contains(e.getKey())) {
                blankValueUtf8(cf, valueByStringCp, e.getKey());
            }
        }
    }

    /**
     * 返回类里仍被 {@code ldc/ldc_w} 指向的字符串值集合。
     *
     * <p>任何方法体无法解析时返回 {@code null}：此时无法确认该值是否还有残留引用，
     * 调用方必须放弃清空明文。</p>
     */
    private static Set<String> stillLoadedValues(ClassFile cf, Map<Integer, String> valueByStringCp) {
        Set<String> out = new HashSet<>();
        for (ClassFile.Member method : cf.methods()) {
            for (ClassFile.Attr attr : method.attributes) {
                if (!"Code".equals(cf.utf8(attr.nameIndex))) {
                    continue;
                }
                byte[] code;
                List<Bytecode.Insn> insns;
                try {
                    code = Bytecode.readCode(attr.info);
                    insns = Bytecode.scan(code);
                } catch (RuntimeException e) {
                    return null;
                }
                for (Bytecode.Insn insn : insns) {
                    if (insn.opcode != LDC && insn.opcode != LDC_W) {
                        continue;
                    }
                    int operand = insn.opcode == LDC ? (code[insn.pos + 1] & 0xFF)
                            : ((code[insn.pos + 1] & 0xFF) << 8 | (code[insn.pos + 2] & 0xFF));
                    String value = valueByStringCp.get(operand);
                    if (value != null) {
                        out.add(value);
                    }
                }
            }
        }
        return out;
    }

    /** 尝试重写一个方法；返回新 Code 内容，未命中/无法安全重写时返回 null。 */
    private byte[] rewriteMethod(ClassFile cf, ClassFile.Attr codeAttr, Map<Integer, String> valueByStringCp,
                                 Map<String, Boolean> safeByValue) {
        List<Bytecode.Insn> insns;
        byte[] code;
        try {
            code = Bytecode.readCode(codeAttr.info);
            insns = Bytecode.scan(code);
        } catch (RuntimeException e) {
            return null;
        }

        Map<Integer, byte[]> edits = new LinkedHashMap<>();
        int methodref = -1;
        for (Bytecode.Insn insn : insns) {
            if (insn.opcode != LDC && insn.opcode != LDC_W) {
                continue;
            }
            int operand = insn.opcode == LDC ? (code[insn.pos + 1] & 0xFF)
                    : ((code[insn.pos + 1] & 0xFF) << 8 | (code[insn.pos + 2] & 0xFF));
            String value = valueByStringCp.get(operand);
            if (value == null || !Boolean.TRUE.equals(safeByValue.get(value))) {
                continue;
            }
            int index = assignIndex(value);
            if (index < 0) {
                continue; // BLOB 已满：这个值不加密，明文留给 stillLoadedValues 兜底
            }
            if (methodref < 0) {
                methodref = cf.addMethodref(vaultClassName, VaultNames.GET_NAME, VaultNames.GET_DESCRIPTOR);
            }
            int intCp = cf.addInteger(index);
            edits.put(insn.pos, ldcThenInvoke(intCp, methodref));
        }
        if (edits.isEmpty()) {
            return null;
        }
        try {
            return CodeRewriter.rewrite(codeAttr.info, edits, cf::utf8);
        } catch (RuntimeException e) {
            System.err.println("POB：跳过无法安全重写的方法（字符串加密）：" + cf.thisName() + "：" + e.getMessage());
            return null;
        }
    }

    private void blankValueUtf8(ClassFile cf, Map<Integer, String> valueByStringCp, String value) {
        List<ClassFile.Cp> cp = cf.constantPool();
        for (int i = 1; i < cp.size(); i++) {
            ClassFile.Cp c = cp.get(i);
            if (c != null && c.tag == ClassFile.C_STRING && value.equals(valueByStringCp.get(i))) {
                cf.setUtf8(c.a, "");
            }
        }
    }

    // ------------------------------------------------------------------
    // 索引与 BLOB
    // ------------------------------------------------------------------

    private int assignIndex(String value) {
        Integer existing = indexByValue.get(value);
        if (existing != null) {
            return existing;
        }
        int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
        long record = 1L + 32 + 2L * byteLength; // 分隔符 + ivHex + cipherHex
        if (blobEstimate + record > VaultNames.BLOB_LIMIT) {
            truncated = true;
            return -1;
        }
        int index = values.size();
        values.add(value);
        indexByValue.put(value, index);
        blobEstimate += record;
        return index;
    }

    private String buildBlob() {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            StringBuilder sb = new StringBuilder((int) blobEstimate);
            sb.append(hex(salt));
            for (int i = 0; i < values.size(); i++) {
                byte[] iv = new byte[16];
                new SecureRandom().nextBytes(iv);
                byte[] cipher = aes(salt, iv, i, values.get(i).getBytes(StandardCharsets.UTF_8), Cipher.ENCRYPT_MODE);
                sb.append(';').append(hex(iv)).append(hex(cipher));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("字符串加密失败", e);
        }
    }

    private static byte[] aes(byte[] salt, byte[] iv, int index, byte[] input, int mode) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        digest.update((byte) ':');
        digest.update(Integer.toString(index).getBytes(StandardCharsets.UTF_8));
        byte[] key = new byte[16];
        System.arraycopy(digest.digest(), 0, key, 0, 16);
        Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
        aes.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return aes.doFinal(input);
    }

    private byte[] buildVaultClass(String blob) throws IOException {
        if (blob.length() > VaultNames.BLOB_LIMIT) {
            throw new IllegalStateException("字符串加密数据超过 BLOB 上限");
        }
        byte[] raw;
        try (InputStream in = StringVault.class.getResourceAsStream(VaultNames.STRING_VAULT_RESOURCE)) {
            if (in == null) {
                throw new IOException("找不到运行时类资源：" + VaultNames.STRING_VAULT_RESOURCE);
            }
            raw = in.readAllBytes();
        }
        ClassFile vault = ClassFile.read(raw);
        vault.setUtf8(vault.utf8Index(VaultNames.STRING_VAULT_PLACEHOLDER), blob);
        vault.renameClass(vaultClassName);
        vault.dropClassAttribute("SourceFile");
        return vault.write();
    }

    // ------------------------------------------------------------------
    // 判定辅助
    // ------------------------------------------------------------------

    private boolean usable(String value) {
        if (value == null || value.isEmpty() || value.length() < rules.encryptStringsMinLength()) {
            return false;
        }
        if (rules.encryptStringsKeep().contains(value)) {
            return false;
        }
        if (classNameForms.contains(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '(' || c == ')' || c == ';' || c == '[' || c == '<' || c == '>') {
                return false;
            }
        }
        return true;
    }

    /**
     * 收集属性内容里出现的所有 u2 值。凡是指向某个 {@code CONSTANT_String} 的引用（字段
     * ConstantValue、注解元素值、BootstrapMethods 实参……）都是以 u2 形式写在这些字节里的，
     * 用这种保守扫描可以保证「有其它引用」的字符串一定被排除。Code 只扫其子属性，不扫字节码
     * 本身，否则 ldc 的操作数会把自己误判成「被其它地方引用」。
     */
    private static Set<Integer> attributeReferences(ClassFile cf) {
        Set<Integer> out = new HashSet<>();
        scanAttributes(cf.utf8Lookup(), cf.attributes(), out);
        for (ClassFile.Member f : cf.fields()) {
            scanAttributes(cf.utf8Lookup(), f.attributes, out);
        }
        for (ClassFile.Member m : cf.methods()) {
            scanAttributes(cf.utf8Lookup(), m.attributes, out);
        }
        return out;
    }

    private static void scanAttributes(java.util.function.IntFunction<String> utf8, List<ClassFile.Attr> attrs,
                                       Set<Integer> out) {
        for (ClassFile.Attr attr : attrs) {
            if ("Code".equals(utf8.apply(attr.nameIndex))) {
                scanCodeSubAttributes(attr.info, out);
            } else {
                scanU2(attr.info, out);
            }
        }
    }

    private static void scanCodeSubAttributes(byte[] info, Set<Integer> out) {
        try {
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(info));
            in.skipBytes(4);
            int codeLength = in.readInt();
            in.skipBytes(codeLength);
            int exceptions = in.readUnsignedShort();
            in.skipBytes(exceptions * 8);
            int attrs = in.readUnsignedShort();
            for (int i = 0; i < attrs; i++) {
                out.add(in.readUnsignedShort());
                int length = in.readInt();
                byte[] body = new byte[length];
                in.readFully(body);
                scanU2(body, out);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Code 属性被截断", e);
        }
    }

    private static void scanU2(byte[] body, Set<Integer> out) {
        for (int i = 0; i + 1 < body.length; i++) {
            out.add(((body[i] & 0xFF) << 8) | (body[i + 1] & 0xFF));
        }
    }

    private static Set<Integer> utf8UsedByNonStringEntries(List<ClassFile.Cp> cp) {
        Set<Integer> out = new HashSet<>();
        for (int i = 1; i < cp.size(); i++) {
            ClassFile.Cp c = cp.get(i);
            if (c == null) {
                continue;
            }
            switch (c.tag) {
                case ClassFile.C_CLASS, ClassFile.C_METHODTYPE, ClassFile.C_MODULE, ClassFile.C_PACKAGE ->
                        out.add(c.a);
                case ClassFile.C_NAMEANDTYPE -> {
                    out.add(c.a);
                    out.add(c.b);
                }
                default -> {
                }
            }
        }
        return out;
    }

    private static Set<String> collectClassNameForms(Renamer renamer, PobRules rules) {
        Set<String> forms = new HashSet<>();
        for (String internal : renamer.classMap().keySet()) {
            addForm(forms, internal);
        }
        for (String internal : renamer.classMap().values()) {
            addForm(forms, internal);
        }
        for (String internal : rules.keptClassNames()) {
            addForm(forms, internal);
        }
        return forms;
    }

    private static void addForm(Set<String> forms, String internal) {
        forms.add(internal);
        forms.add(internal.replace('/', '.'));
    }

    private static byte[] ldcThenInvoke(int intCp, int methodref) {
        return new byte[]{
                (byte) LDC_W, (byte) (intCp >>> 8), (byte) intCp,
                (byte) INVOKESTATIC, (byte) (methodref >>> 8), (byte) methodref};
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}