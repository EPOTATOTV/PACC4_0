package com.potatotv.pob;

import com.potatotv.pob.runtime.ConcatBootstrap;
import com.potatotv.pob.runtime.StringVault;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 字符串加密：把明文串从产物里挪走，运行时再解密。
 *
 * <h2>三条路径</h2>
 * <ol>
 *   <li><b>ldc</b>：{@code ldc/ldc_w <String>} 换成 {@code ldc_w <int>; invokestatic PobVault.get(I)}；</li>
 *   <li><b>字段常量</b>：{@code static final String X = "..."} 的 {@code ConstantValue} 属性删掉，
 *       改在 {@code <clinit>} 里用解密串赋值（{@code ldc_w int; invokestatic get; putstatic}）；</li>
 *   <li><b>indy 拼接</b>：javac 9+ 的 {@code makeConcatWithConstants} 字面量藏在 BootstrapMethods
 *       的 recipe 里，把 recipe 换成密文并把引导方法指到注入的 {@code PobConcat}，链接时解密。</li>
 * </ol>
 *
 * <h2>安全判定（宁少勿错）</h2>
 * <p>一个字符串常量只有在下列条件全部成立时才加密：</p>
 * <ul>
 *   <li>该 {@code CONSTANT_String} 的 UTF-8 没有被任何非 String 的常量池条目引用
 *       （否则它可能是类名/描述符，改了就断链）；</li>
 *   <li>该 {@code CONSTANT_String} 的下标没有出现在任何「非 Code 方法体」的属性里
 *       （字段 ConstantValue、注解元素值、BootstrapMethods 实参……用「扫一遍属性字节」保守判定）；
 *       字段常量会先把 ConstantValue 摘掉再判定，因此它不会被自己误伤；</li>
 *   <li>不是白名单串、长度足够、不含描述符字符，且不是「内部类名样串」；</li>
 *   <li>它确实是某个 {@code ldc} 的实参（或已成功提升为字段赋值 / 拼接配方），
 *       且所在方法能被 {@link CodeRewriter} 安全重写。</li>
 * </ul>
 * <p>题目要求的「不加密 Class.forName 的类名字符串」由「内部类名样串」这一条覆盖。</p>
 *
 * <h2>BLOB 容量与优先级</h2>
 * <p>BLOB 是单个 UTF-8 常量，受 u2 的 65535 字节上限约束。候选串通常远多于能装下的量，
 * 因此先按「URL / 绝对路径 / 主机名」优先级把名额留给最敏感的串，再填其余；装不下的
 * 保持明文（宁少勿错，绝不产出非法 class）。</p>
 */
final class StringEncryptor {

    private static final int LDC = 0x12;
    private static final int LDC_W = 0x13;
    private static final int INVOKESTATIC = 0xb8;
    private static final int PUTSTATIC = 0xb3;
    private static final int RETURN = 0xb1;
    private static final int ACC_STATIC = 0x0008;
    private static final int REF_INVOKE_STATIC = 6;

    private static final String CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory";
    private static final String CONCAT_FACTORY_METHOD = "makeConcatWithConstants";
    private static final String CONCAT_BOOTSTRAP_NAME = "bootstrap";

    private final PobRules rules;
    private final String vaultClassName;
    private final String concatClassName;
    private final Set<String> classNameForms;
    private final byte[] salt = new byte[16];

    private final Map<String, Integer> indexByValue = new LinkedHashMap<>();
    private final List<String> values = new ArrayList<>();
    private long blobEstimate = 33; // saltHex(32) + 第一个分隔符
    private boolean truncated;
    private boolean sealed;
    private boolean concatUsed;

    StringEncryptor(String targetPackage, PobRules rules, Renamer renamer) {
        this.rules = rules;
        this.vaultClassName = targetPackage + '/' + VaultNames.STRING_VAULT_SIMPLE;
        this.concatClassName = targetPackage + '/' + VaultNames.CONCAT_BOOTSTRAP_SIMPLE;
        this.classNameForms = collectClassNameForms(renamer, rules);
        new SecureRandom().nextBytes(salt);
    }

    /**
     * 就地加密所有类里的候选字符串。
     *
     * @return 注入产物用的解密库类字节；没有任何字符串进入 BLOB 时返回 null
     */
    byte[] encrypt(List<ClassFile> classes) throws IOException {
        List<ClassPlan> plans = new ArrayList<>();
        Set<String> candidates = new LinkedHashSet<>();
        for (ClassFile cf : classes) {
            ClassPlan plan = planClass(cf);
            plans.add(plan);
            for (Map.Entry<String, Boolean> e : plan.safeByValue.entrySet()) {
                if (Boolean.TRUE.equals(e.getValue())) {
                    candidates.add(e.getKey());
                }
            }
            for (FieldHoist h : plan.hoists) {
                candidates.add(h.value);
            }
        }

        // 预分配：URL / 绝对路径 / 主机名优先占名额，避免被无关长串挤掉。
        List<String> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingInt(StringEncryptor::priority));
        for (String value : ordered) {
            assignIndex(value);
        }
        sealed = true;

        for (ClassPlan plan : plans) {
            dropUnfundedHoists(plan);
        }
        for (ClassPlan plan : plans) {
            rewriteLdcSites(plan);
        }
        for (ClassPlan plan : plans) {
            injectFieldConstants(plan);
        }
        for (ClassPlan plan : plans) {
            blankPlaintext(plan);
        }
        for (ClassPlan plan : plans) {
            encryptConcatRecipes(plan);
        }

        if (truncated) {
            System.err.println("POB：字符串加密数据接近 BLOB 上限，优先级较低的部分字符串保持明文");
        }
        return values.isEmpty() ? null : buildVaultClass(buildBlob());
    }

    /** 本次是否改写了 invokedynamic 拼接；是则调用方要注入 {@link #concatClass()}。 */
    byte[] concatClass() throws IOException {
        if (!concatUsed) {
            return null;
        }
        ClassFile cf = ClassFile.read(runtimeClass(VaultNames.CONCAT_BOOTSTRAP_RESOURCE));
        cf.setUtf8(cf.utf8Index(VaultNames.CONCAT_BOOTSTRAP_PLACEHOLDER), hex(salt));
        cf.renameClass(concatClassName);
        cf.dropClassAttribute("SourceFile");
        return cf.write();
    }

    // ------------------------------------------------------------------
    // 单类：规划
    // ------------------------------------------------------------------

    /** 一个类的加密计划：字段提升候选、ldc 候选与安全判定结果。规划后不再依赖原属性。 */
    private static final class ClassPlan {
        final ClassFile cf;
        final Map<Integer, String> valueByStringCp = new HashMap<>();
        final Map<String, Boolean> safeByValue = new HashMap<>();
        final List<FieldHoist> hoists = new ArrayList<>();

        ClassPlan(ClassFile cf) {
            this.cf = cf;
        }

        /** 回滚一个字段提升：把 ConstantValue 放回字段，并标记该值不可再动（不加密 / 不清明文）。 */
        void restore(FieldHoist h) {
            h.field.attributes.add(h.constantAttr);
            safeByValue.put(h.value, false);
        }
    }

    /** 一个待提升的字段常量：原属性先摘下来，注入 {@code <clinit>} 时不再需要它。 */
    private static final class FieldHoist {
        final ClassFile.Member field;
        final ClassFile.Attr constantAttr;
        final String value;

        FieldHoist(ClassFile.Member field, ClassFile.Attr constantAttr, String value) {
            this.field = field;
            this.constantAttr = constantAttr;
            this.value = value;
        }
    }

    private ClassPlan planClass(ClassFile cf) {
        ClassPlan plan = new ClassPlan(cf);

        // 先把字段常量的 ConstantValue 摘掉：这样 attributeReferences 不会把它自己算成
        // 「非 Code 引用」，字段初值串才可能被加密。摘掉的属性留着重放（名额不足 / 注入失败时回滚）。
        boolean hoistable = clinitPrependsSafely(cf);
        if (hoistable) {
            for (ClassFile.Member field : cf.fields()) {
                if (!"Ljava/lang/String;".equals(cf.utf8(field.descriptorIndex))) {
                    continue;
                }
                ClassFile.Attr constant = findAttribute(cf, field.attributes, "ConstantValue");
                if (constant == null || constant.info.length != 2) {
                    continue;
                }
                int stringIndex = u2At(constant.info, 0);
                ClassFile.Cp c = cf.cp(stringIndex);
                if (c == null || c.tag != ClassFile.C_STRING) {
                    continue;
                }
                String value = cf.utf8(c.a);
                if (!usable(value)) {
                    continue;
                }
                field.attributes.remove(constant);
                plan.hoists.add(new FieldHoist(field, constant, value));
            }
        }

        List<ClassFile.Cp> cp = cf.constantPool();
        Set<Integer> referenced = attributeReferences(cf);
        Set<Integer> nonStringUtf8 = utf8UsedByNonStringEntries(cf);
        for (int i = 1; i < cp.size(); i++) {
            ClassFile.Cp c = cp.get(i);
            if (c == null || c.tag != ClassFile.C_STRING) {
                continue;
            }
            String value = cf.utf8(c.a);
            plan.valueByStringCp.put(i, value);
            boolean safe = usable(value) && !referenced.contains(i) && !nonStringUtf8.contains(c.a);
            plan.safeByValue.merge(value, safe, (a, b) -> a && b);
        }
        return plan;
    }

    /** 名额分配后回滚没能进入 BLOB 的字段提升：把 ConstantValue 放回去并标记该值不可动。 */
    private void dropUnfundedHoists(ClassPlan plan) {
        for (FieldHoist h : plan.hoists) {
            Integer index = indexByValue.get(h.value);
            if (index == null || index < 0) {
                plan.restore(h);
            }
        }
    }

    private void rewriteLdcSites(ClassPlan plan) {
        if (plan.valueByStringCp.isEmpty()) {
            return;
        }
        ClassFile cf = plan.cf;
        for (ClassFile.Member method : cf.methods()) {
            for (ClassFile.Attr attr : method.attributes) {
                if (!"Code".equals(cf.utf8(attr.nameIndex))) {
                    continue;
                }
                byte[] rewritten = rewriteMethod(cf, attr, plan.valueByStringCp, plan.safeByValue);
                if (rewritten != null) {
                    attr.info = rewritten;
                }
            }
        }
    }

    /** 字段提升落地：把解析串在 {@code <clinit>} 里赋回字段。任何一步不成立就回滚该字段。 */
    private void injectFieldConstants(ClassPlan plan) {
        if (plan.hoists.isEmpty()) {
            return;
        }
        ClassFile cf = plan.cf;
        List<FieldHoist> pending = new ArrayList<>();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int methodref = cf.addMethodref(vaultClassName, VaultNames.GET_NAME, VaultNames.GET_DESCRIPTOR);
        for (FieldHoist h : plan.hoists) {
            Integer index = indexByValue.get(h.value);
            if (index == null || index < 0) {
                continue; // 已在 dropUnfundedHoists 回滚
            }
            int intCp = cf.addInteger(index);
            int fieldref = cf.addFieldref(cf.thisName(),
                    cf.utf8(h.field.nameIndex), cf.utf8(h.field.descriptorIndex));
            emitLdcInvokePut(body, intCp, methodref, fieldref);
            pending.add(h);
        }
        if (pending.isEmpty()) {
            return;
        }

        ClassFile.Member clinit = findClinit(cf);
        byte[] injected = body.toByteArray();
        if (clinit == null) {
            byte[] code = new byte[injected.length + 1];
            System.arraycopy(injected, 0, code, 0, injected.length);
            code[injected.length] = (byte) RETURN;
            cf.addMethod(ClassFile.newMethod(ACC_STATIC, cf.utf8Index("<clinit>"), cf.utf8Index("()V"),
                    List.of(new ClassFile.Attr(cf.utf8Index("Code"), codeAttribute(code, 1, 0)))));
            return;
        }

        for (ClassFile.Attr attr : clinit.attributes) {
            if (!"Code".equals(cf.utf8(attr.nameIndex))) {
                continue;
            }
            try {
                byte[] code = Bytecode.readCode(attr.info);
                Bytecode.Insn first = Bytecode.scan(code).get(0);
                byte[] replacement = new byte[injected.length + first.length];
                System.arraycopy(injected, 0, replacement, 0, injected.length);
                System.arraycopy(code, first.pos, replacement, injected.length, first.length);
                // 注入序列瞬时净栈 +1（ldc 推入，invokestatic 换栈顶，putstatic 弹掉）
                attr.info = CodeRewriter.rewrite(attr.info, Map.of(first.pos, replacement), 1, cf::utf8);
            } catch (RuntimeException e) {
                System.err.println("POB：字段常量注入失败，已回滚：" + cf.thisName() + "：" + e.getMessage());
                for (FieldHoist h : pending) {
                    plan.restore(h);
                }
            }
            return;
        }
        for (FieldHoist h : pending) {
            plan.restore(h);
        }
    }

    private void blankPlaintext(ClassPlan plan) {
        if (plan.valueByStringCp.isEmpty()) {
            return;
        }
        Set<String> stillLoaded = stillLoadedValues(plan.cf, plan.valueByStringCp);
        if (stillLoaded == null) {
            return;
        }
        for (Map.Entry<String, Boolean> e : plan.safeByValue.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue()) && !stillLoaded.contains(e.getKey())) {
                blankValueUtf8(plan.cf, plan.valueByStringCp, e.getKey());
            }
        }
    }

    /** 把已确认安全的 indy 拼接配方换成密文，并把引导方法指到注入的 PobConcat。 */
    private void encryptConcatRecipes(ClassPlan plan) {
        ClassFile cf = plan.cf;
        Set<Integer> replaced = new LinkedHashSet<>();
        for (ClassFile.Attr attr : cf.attributes()) {
            if (!"BootstrapMethods".equals(cf.utf8(attr.nameIndex))) {
                continue;
            }
            int count = u2At(attr.info, 0);
            int off = 2;
            int bootstrapHandle = -1;
            for (int i = 0; i < count; i++) {
                int methodRefIndex = u2At(attr.info, off);
                int argCount = u2At(attr.info, off + 2);
                int argsOffset = off + 4;
                if (argCount >= 1 && isConcatBootstrap(cf, methodRefIndex)) {
                    int recipeIndex = u2At(attr.info, argsOffset);
                    ClassFile.Cp c = cf.cp(recipeIndex);
                    if (c != null && c.tag == ClassFile.C_STRING) {
                        String recipe = cf.utf8(c.a);
                        if (usable(recipe)) {
                            if (bootstrapHandle < 0) {
                                bootstrapHandle = concatBootstrapHandle(cf, methodRefIndex);
                            }
                            putU2(attr.info, off, bootstrapHandle);
                            putU2(attr.info, argsOffset, cf.addString(encodeRecipe(recipe)));
                            replaced.add(recipeIndex);
                            concatUsed = true;
                        }
                    }
                }
                off = argsOffset + 2 * argCount;
            }
        }
        if (!replaced.isEmpty()) {
            cleanRecipePlaintext(cf, replaced);
        }
    }

    /**
     * 抹掉已被替换下来的拼接配方明文。
     *
     * <p>引导实参改指密文后，原 {@code CONSTANT_String} 可能仍被别处引用（同一段拼接配方在多个
     * 方法点共享，或与普通字面量共文），因此必须重新确认无人引用，且它不与成员名/描述符等结构
     * 共用 UTF-8，才能安全地把明文清空。</p>
     */
    private void cleanRecipePlaintext(ClassFile cf, Set<Integer> recipeStrings) {
        Set<Integer> operands = ldcOperands(cf);
        if (operands == null) {
            return;
        }
        Set<Integer> referenced = attributeReferences(cf);
        Set<Integer> nonStringUtf8 = utf8UsedByNonStringEntries(cf);
        for (int recipe : recipeStrings) {
            ClassFile.Cp c = cf.cp(recipe);
            if (c == null || c.tag != ClassFile.C_STRING) {
                continue;
            }
            if (referenced.contains(recipe) || operands.contains(recipe)
                    || nonStringUtf8.contains(c.a) || countStringsWithUtf8(cf, c.a) != 1) {
                continue;
            }
            cf.setUtf8(c.a, "");
        }
    }

    /** 指向同一条 UTF-8 的 {@code CONSTANT_String} 条目数；用于确认清明文不会误伤同文串。 */
    private static int countStringsWithUtf8(ClassFile cf, int utf8Index) {
        int count = 0;
        List<ClassFile.Cp> cp = cf.constantPool();
        for (int i = 1; i < cp.size(); i++) {
            ClassFile.Cp c = cp.get(i);
            if (c != null && c.tag == ClassFile.C_STRING && c.a == utf8Index) {
                count++;
            }
        }
        return count;
    }

    /** 该引导方法是不是 javac 的字符串拼接工厂（REF_invokeStatic → StringConcatFactory.makeConcatWithConstants）。 */
    private static boolean isConcatBootstrap(ClassFile cf, int methodHandleIndex) {
        ClassFile.Cp handle = cf.cp(methodHandleIndex);
        if (handle == null || handle.tag != ClassFile.C_METHODHANDLE || handle.a != REF_INVOKE_STATIC) {
            return false;
        }
        ClassFile.Cp ref = cf.cp(handle.b);
        if (ref == null || ref.tag != ClassFile.C_METHODREF) {
            return false;
        }
        if (!CONCAT_FACTORY.equals(cf.className(ref.a))) {
            return false;
        }
        ClassFile.Cp nat = cf.cp(ref.b);
        return nat != null && CONCAT_FACTORY_METHOD.equals(cf.utf8(nat.a));
    }

    /**
     * 为拼接引导新建一个方法句柄，指向 {@code PobConcat.bootstrap}，描述符沿用原引导方法。
     *
     * <p>必须新建而不是就地改：同一个 {@code CONSTANT_MethodHandle} 常被本类所有拼接点共享，
     * 就地改会把还没处理的点一并改掉。</p>
     */
    private int concatBootstrapHandle(ClassFile cf, int originalHandleIndex) {
        ClassFile.Cp handle = cf.cp(originalHandleIndex);
        ClassFile.Cp ref = cf.cp(handle.b);
        ClassFile.Cp nat = cf.cp(ref.b);
        String descriptor = cf.utf8(nat.b);
        int methodref = cf.addMethodref(concatClassName, CONCAT_BOOTSTRAP_NAME, descriptor);
        return cf.addMethodHandle(REF_INVOKE_STATIC, methodref);
    }

    /** recipe 密文：{@code ivHex(32) + cipherHex}，与运行时 PobConcat 的索引用 0 对齐。 */
    private String encodeRecipe(String recipe) {
        try {
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            byte[] cipher = aes(salt, iv, 0, recipe.getBytes(StandardCharsets.UTF_8), Cipher.ENCRYPT_MODE);
            return hex(iv) + hex(cipher);
        } catch (Exception e) {
            throw new IllegalStateException("拼接配方加密失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 单类：ldc 改写与判定辅助
    // ------------------------------------------------------------------

    /**
     * 返回类里仍被 {@code ldc/ldc_w} 指向的字符串值集合。
     *
     * <p>任何方法体无法解析时返回 {@code null}：此时无法确认该值是否还有残留引用，
     * 调用方必须放弃清空明文。</p>
     */
    private static Set<String> stillLoadedValues(ClassFile cf, Map<Integer, String> valueByStringCp) {
        Set<Integer> operands = ldcOperands(cf);
        if (operands == null) {
            return null;
        }
        Set<String> out = new HashSet<>();
        for (int operand : operands) {
            String value = valueByStringCp.get(operand);
            if (value != null) {
                out.add(value);
            }
        }
        return out;
    }

    /**
     * 类里所有 {@code ldc/ldc_w} 的常量池操作数下标。
     *
     * <p>任何方法体无法解析时返回 {@code null}：调用方必须放弃「某个常量已无人引用」的判断。</p>
     */
    private static Set<Integer> ldcOperands(ClassFile cf) {
        Set<Integer> out = new HashSet<>();
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
                    out.add(operand);
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
            return CodeRewriter.rewrite(codeAttr.info, edits, 0, cf::utf8);
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
    // 索引、BLOB 与优先级
    // ------------------------------------------------------------------

    private int assignIndex(String value) {
        Integer existing = indexByValue.get(value);
        if (existing != null) {
            return existing;
        }
        if (sealed) {
            return -1; // 预分配已结束：不再临时追加，保证所有类的下标视图一致
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

    /**
     * 加密优先级：越小越先占 BLOB 名额。
     *
     * <p>0 = URL / 路径（含 {@code ://}、以 {@code /} 开头或含 {@code /}）；1 = 主机名样串
     * （只含域名可用字符且带点）；2 = 其余。API 地址、资源路径、密钥上下文基本落在 0/1，
     * 容量不足时优先保住它们。</p>
     */
    private static int priority(String value) {
        if (value.contains("://") || value.indexOf('/') >= 0) {
            return 0;
        }
        if (looksLikeHost(value)) {
            return 1;
        }
        return 2;
    }

    private static boolean looksLikeHost(String value) {
        if (value.length() < 4 || value.indexOf('.') < 0) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-'
                    || c == '_' || c == ':' || c == '/';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private String buildBlob() {
        try {
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
        ClassFile vault = ClassFile.read(runtimeClass(VaultNames.STRING_VAULT_RESOURCE));
        vault.setUtf8(vault.utf8Index(VaultNames.STRING_VAULT_PLACEHOLDER), blob);
        vault.renameClass(vaultClassName);
        vault.dropClassAttribute("SourceFile");
        return vault.write();
    }

    private static byte[] runtimeClass(String resource) throws IOException {
        try (InputStream in = StringVault.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("找不到运行时类资源：" + resource);
            }
            return in.readAllBytes();
        }
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
            if (c == '(' || c == ')' || c == ';' || c == '[' || c == '<' || c == '>') {
                return false;
            }
        }
        // 只放过真正形如 JVM 内部类名的串（java/lang/String、com/foo/Bar$Inner）。
        // 早期实现是「含 '/' 一律跳过」，把 URL、以 '/' 开头的 API 路径（/api/...）与资源
        // 路径一并误杀，发行件里 API 地址仍是明文（验收 V01 要求反编译看不到 API 路径）。
        // 改成按整串形态判定：只要出现 ':'、'.'、'-'、空格、'?'、前导/尾随 '/' 或非 ASCII，
        // 就不再可能是类名，应当正常加密。
        return !looksLikeInternalClassName(value);
    }

    /**
     * 整串由 '/' 分段、且每段都只含 ASCII Java 标识符字符时，视为 JVM 内部类名样串。
     *
     * <p>这类串可能是 {@code Class.forName} / {@code getResourceAsStream} 的实参，保守保持
     * 明文；URL（含 {@code :}）、以 '/' 开头的 API 路径（首段为空）、资源路径（含 '.'、'-'
     * 等）都不可能满足该形态，因此会走加密。</p>
     */
    private static boolean looksLikeInternalClassName(String value) {
        if (value.indexOf('/') < 0) {
            return false;
        }
        int start = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i < value.length() && value.charAt(i) != '/') {
                if (!isIdentifierChar(value.charAt(i))) {
                    return false;
                }
                continue;
            }
            if (i == start) {
                return false; // 空段：前导、尾随或连续斜杠
            }
            start = i + 1;
        }
        return true;
    }

    private static boolean isIdentifierChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '$';
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
            DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(info));
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

    private static Set<Integer> utf8UsedByNonStringEntries(ClassFile cf) {
        Set<Integer> out = new HashSet<>();
        List<ClassFile.Cp> cp = cf.constantPool();
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
        // 常量池的 UTF-8 是去重的：字符串字面量若与某个成员名/描述符/属性名同文，会共用同一条
        // 条目。清明文时若改到这些条目就会破坏类结构，因此凡是「类结构直接引用」的 UTF-8 一律排除。
        for (ClassFile.Attr a : cf.attributes()) {
            out.add(a.nameIndex);
        }
        for (ClassFile.Member f : cf.fields()) {
            out.add(f.nameIndex);
            out.add(f.descriptorIndex);
            for (ClassFile.Attr a : f.attributes) {
                out.add(a.nameIndex);
            }
        }
        for (ClassFile.Member m : cf.methods()) {
            out.add(m.nameIndex);
            out.add(m.descriptorIndex);
            for (ClassFile.Attr a : m.attributes) {
                out.add(a.nameIndex);
                if ("Code".equals(cf.utf8(a.nameIndex))) {
                    collectCodeSubAttributeNames(a.info, out);
                }
            }
        }
        return out;
    }

    private static void collectCodeSubAttributeNames(byte[] info, Set<Integer> out) {
        try {
            DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(info));
            in.skipBytes(4);
            int codeLength = in.readInt();
            in.skipBytes(codeLength);
            int exceptions = in.readUnsignedShort();
            in.skipBytes(exceptions * 8);
            int attrs = in.readUnsignedShort();
            for (int i = 0; i < attrs; i++) {
                out.add(in.readUnsignedShort());
                int length = in.readInt();
                in.skipBytes(length);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Code 属性被截断", e);
        }
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

    // ------------------------------------------------------------------
    // 属性/常量池小工具
    // ------------------------------------------------------------------

    private static ClassFile.Attr findAttribute(ClassFile cf, List<ClassFile.Attr> attrs, String name) {
        for (ClassFile.Attr attr : attrs) {
            if (name.equals(cf.utf8(attr.nameIndex))) {
                return attr;
            }
        }
        return null;
    }

    private static ClassFile.Member findClinit(ClassFile cf) {
        for (ClassFile.Member m : cf.methods()) {
            if ("<clinit>".equals(cf.utf8(m.nameIndex))) {
                return m;
            }
        }
        return null;
    }

    /**
     * 已有 {@code <clinit>} 时能否安全地在开头插入赋值序列。
     *
     * <p>插入点落在偏移 0，异常表若从这里起就算「把注入代码也纳进 try」，语义会变，因此
     * 只要异常表非空就整体放弃字段提升（字段常量保持原样）。没有 {@code <clinit>} 则直接新建。</p>
     */
    private static boolean clinitPrependsSafely(ClassFile cf) {
        ClassFile.Member clinit = findClinit(cf);
        if (clinit == null) {
            return true;
        }
        for (ClassFile.Attr attr : clinit.attributes) {
            if (!"Code".equals(cf.utf8(attr.nameIndex))) {
                continue;
            }
            try {
                DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(attr.info));
                in.skipBytes(4);
                int codeLength = in.readInt();
                in.skipBytes(codeLength);
                return in.readUnsignedShort() == 0;
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    /** ldc_w int; invokestatic get; putstatic field。 */
    private static void emitLdcInvokePut(ByteArrayOutputStream out, int intCp, int methodref, int fieldref) {
        out.write(LDC_W);
        out.write((intCp >>> 8) & 0xFF);
        out.write(intCp & 0xFF);
        out.write(INVOKESTATIC);
        out.write((methodref >>> 8) & 0xFF);
        out.write(methodref & 0xFF);
        out.write(PUTSTATIC);
        out.write((fieldref >>> 8) & 0xFF);
        out.write(fieldref & 0xFF);
    }

    /** 直线代码（无跳转目标）不需要 StackMapTable，异常表与子属性都为空。 */
    private static byte[] codeAttribute(byte[] code, int maxStack, int maxLocals) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(code.length + 12);
        out.write((maxStack >>> 8) & 0xFF);
        out.write(maxStack & 0xFF);
        out.write((maxLocals >>> 8) & 0xFF);
        out.write(maxLocals & 0xFF);
        out.write((code.length >>> 24) & 0xFF);
        out.write((code.length >>> 16) & 0xFF);
        out.write((code.length >>> 8) & 0xFF);
        out.write(code.length & 0xFF);
        out.write(code, 0, code.length);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);
        return out.toByteArray();
    }

    static int u2At(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static void putU2(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
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
