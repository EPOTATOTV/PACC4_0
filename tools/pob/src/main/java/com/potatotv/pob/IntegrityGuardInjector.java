package com.potatotv.pob;

import com.potatotv.pob.runtime.IntegrityGuard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * 完整性校验注入（保守实现）。规则 {@code integrity = true} 时对 {@code enhance} 标记的类生效。
 *
 * <p>不往整个方法体里撒校验调用，只在类初始化入口 {@code <clinit>} 调一次
 * {@code PobGuard.check()}：没有 {@code <clinit>} 就新建一个只含
 * {@code invokestatic; return} 的方法——直线代码不需要 StackMapTable，因此绝不会破坏验证。
 * 已有 {@code <clinit>} 时在第一条指令前插入调用（净栈 0）。</p>
 *
 * <p>哈希在所有变换完成之后、Guard 类构建之前计算，被校验类的字节此后不再改动，故不存在
 * 「哈希包含自己」的循环。</p>
 */
final class IntegrityGuardInjector {

    private static final int ACC_STATIC = 0x0008;
    private static final int INVOKESTATIC = 0xb8;
    private static final int RETURN = 0xb1;

    private final String guardClassName;

    IntegrityGuardInjector(String targetPackage) {
        this.guardClassName = targetPackage + '/' + VaultNames.INTEGRITY_GUARD_SIMPLE;
    }

    /**
     * @param marked 需要校验的类
     * @return 注入产物用的 Guard 类字节；没有成功注入任何类时返回 null
     */
    byte[] inject(List<ClassFile> marked) throws IOException {
        StringBuilder blob = new StringBuilder();
        for (ClassFile cf : marked) {
            if (!injectCheck(cf)) {
                continue;
            }
            byte[] bytes = cf.write();
            if (blob.length() > 0) {
                blob.append(';');
            }
            blob.append(cf.thisName().replace('/', '.')).append(':').append(sha256Hex(bytes));
        }
        if (blob.length() == 0) {
            return null;
        }
        return buildGuard(blob.toString());
    }

    private boolean injectCheck(ClassFile cf) {
        int methodref = cf.addMethodref(guardClassName, VaultNames.CHECK_NAME, VaultNames.CHECK_DESCRIPTOR);
        byte[] call = {(byte) INVOKESTATIC, (byte) (methodref >>> 8), (byte) methodref};
        ClassFile.Member clinit = findClinit(cf);
        if (clinit == null) {
            return addClinit(cf, call);
        }
        for (ClassFile.Attr attr : clinit.attributes) {
            if (!"Code".equals(cf.utf8(attr.nameIndex))) {
                continue;
            }
            try {
                byte[] code = Bytecode.readCode(attr.info);
                Bytecode.Insn first = Bytecode.scan(code).get(0);
                if (Bytecode.isShortBranch(first.opcode) || Bytecode.isWideBranch(first.opcode)
                        || Bytecode.isSwitch(first.opcode)) {
                    throw new UnsupportedOperationException("<clinit> 首条指令是跳转");
                }
                byte[] replacement = new byte[call.length + first.length];
                System.arraycopy(call, 0, replacement, 0, call.length);
                System.arraycopy(code, first.pos, replacement, call.length, first.length);
                attr.info = CodeRewriter.rewrite(attr.info, Map.of(first.pos, replacement), 0, cf::utf8);
                return true;
            } catch (RuntimeException e) {
                System.err.println("POB：跳过无法注入完整性校验的类：" + cf.thisName() + "：" + e.getMessage());
                return false;
            }
        }
        return false;
    }

    private boolean addClinit(ClassFile cf, byte[] call) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(call.length + 1);
        body.write(call, 0, call.length);
        body.write(RETURN);
        byte[] info = codeAttribute(body.toByteArray(), 0, 0);
        ClassFile.Member clinit = ClassFile.newMethod(ACC_STATIC, cf.utf8Index("<clinit>"),
                cf.utf8Index("()V"),
                List.of(new ClassFile.Attr(cf.utf8Index("Code"), info)));
        cf.addMethod(clinit);
        return true;
    }

    /** 直线代码（无跳转目标）不需要 StackMapTable，异常表与子属性都为空。 */
    private static byte[] codeAttribute(byte[] code, int maxStack, int maxLocals) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(code.length + 12);
        writeU2(out, maxStack);
        writeU2(out, maxLocals);
        writeU4(out, code.length);
        out.write(code, 0, code.length);
        writeU2(out, 0);
        writeU2(out, 0);
        return out.toByteArray();
    }

    private ClassFile.Member findClinit(ClassFile cf) {
        for (ClassFile.Member m : cf.methods()) {
            if ("<clinit>".equals(cf.utf8(m.nameIndex))) {
                return m;
            }
        }
        return null;
    }

    private byte[] buildGuard(String blob) throws IOException {
        if (blob.getBytes(StandardCharsets.UTF_8).length > VaultNames.BLOB_LIMIT) {
            throw new IllegalStateException("完整性校验数据超过 BLOB 上限");
        }
        byte[] raw;
        try (InputStream in = IntegrityGuard.class.getResourceAsStream(VaultNames.INTEGRITY_GUARD_RESOURCE)) {
            if (in == null) {
                throw new IOException("找不到运行时类资源：" + VaultNames.INTEGRITY_GUARD_RESOURCE);
            }
            raw = in.readAllBytes();
        }
        ClassFile guard = ClassFile.read(raw);
        guard.setUtf8(guard.utf8Index(VaultNames.INTEGRITY_GUARD_PLACEHOLDER), blob);
        guard.renameClass(guardClassName);
        guard.dropClassAttribute("SourceFile");
        return guard.write();
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static void writeU2(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeU4(ByteArrayOutputStream out, long v) {
        out.write((int) ((v >>> 24) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) (v & 0xFF));
    }
}