package com.potatotv.pob;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 垃圾代码注入（保守子集）。规则 {@code bogus_code = true} 时对 {@code enhance} 标记的类生效。
 *
 * <p>文档里的「不透明谓词 + 垃圾代码」依赖插入分支，分支意味着新的基本块，需要重新生成
 * StackMapTable frame——那超出本次实现的能力边界（见 {@link CodeRewriter} 的说明）。
 * 因此这里只插入<b>不含跳转、净栈为 0</b> 的指令序列：在每个 return 前塞
 * {@code iconst_0; iconst_1; iadd; pop}。它不产生新目标、不改变任何 frame 的类型信息，
 * 只提高阅读与反编译成本。这是完整不透明谓词的一个严格子集。</p>
 */
final class BogusInsert {

    /** iconst_0; iconst_1; iadd; pop —— 净栈 0，瞬时最大深度 2。 */
    private static final byte[] JUNK = {(byte) 0x03, (byte) 0x04, (byte) 0x60, (byte) 0x57};
    private static final int EXTRA_STACK = 2;

    private BogusInsert() {
    }

    static void apply(ClassFile cf) {
        for (ClassFile.Member method : cf.methods()) {
            for (ClassFile.Attr attr : method.attributes) {
                if (!"Code".equals(cf.utf8(attr.nameIndex))) {
                    continue;
                }
                try {
                    rewrite(cf, attr);
                } catch (RuntimeException e) {
                    System.err.println("POB：跳过无法注入垃圾代码的方法：" + cf.thisName() + "：" + e.getMessage());
                }
            }
        }
    }

    private static void rewrite(ClassFile cf, ClassFile.Attr attr) {
        byte[] code = Bytecode.readCode(attr.info);
        List<Bytecode.Insn> insns = Bytecode.scan(code);
        Map<Integer, byte[]> edits = new LinkedHashMap<>();
        for (Bytecode.Insn insn : insns) {
            if (!Bytecode.isReturn(insn.opcode)) {
                continue;
            }
            // 用「垃圾 + 原指令」替换原指令，等效于在它前面插入垃圾；原 return 语义不变
            byte[] replacement = new byte[JUNK.length + insn.length];
            System.arraycopy(JUNK, 0, replacement, 0, JUNK.length);
            System.arraycopy(code, insn.pos, replacement, JUNK.length, insn.length);
            edits.put(insn.pos, replacement);
        }
        if (!edits.isEmpty()) {
            attr.info = CodeRewriter.rewrite(attr.info, edits, EXTRA_STACK, cf::utf8);
        }
    }
}