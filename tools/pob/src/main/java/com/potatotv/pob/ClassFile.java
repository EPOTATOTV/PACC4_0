package com.potatotv.pob;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 单个 {@code .class} 文件的读写模型。
 *
 * <p>核心取巧点：混淆全程<b>不改动常量池下标</b>。类名、描述符、泛型签名的改写直接在
 * UTF-8 常量上原地替换，于是 Code 属性里的指令操作数、StackMapTable、异常表、
 * BootstrapMethods 全都不需要解析或重写——它们引用的下标一个都没变。</p>
 *
 * <p>唯一需要动下标的是成员名（方法名/字段名）：一个 UTF-8 条目可能与字符串常量
 * 共用，原地改写会连带改坏字符串，所以改为追加新 UTF-8 条目并让 NameAndType /
 * 声明的 name_index 指过去。</p>
 */
final class ClassFile {

    static final int MAGIC = 0xCAFEBABE;

    // ---- 常量池 tag（JVMS 4.4）----
    static final int C_UTF8 = 1;
    static final int C_INT = 3;
    static final int C_FLOAT = 4;
    static final int C_LONG = 5;
    static final int C_DOUBLE = 6;
    static final int C_CLASS = 7;
    static final int C_STRING = 8;
    static final int C_FIELDREF = 9;
    static final int C_METHODREF = 10;
    static final int C_IMETHODREF = 11;
    static final int C_NAMEANDTYPE = 12;
    static final int C_METHODHANDLE = 15;
    static final int C_METHODTYPE = 16;
    static final int C_DYNAMIC = 17;
    static final int C_INVOKEDYNAMIC = 18;
    static final int C_MODULE = 19;
    static final int C_PACKAGE = 20;

    // ---- 访问标志 ----
    static final int ACC_PUBLIC = 0x0001;
    static final int ACC_PRIVATE = 0x0002;
    static final int ACC_PROTECTED = 0x0004;
    static final int ACC_STATIC = 0x0008;
    static final int ACC_NATIVE = 0x0100;
    static final int ACC_ABSTRACT = 0x0400;
    static final int ACC_ENUM = 0x4000;

    /** 只泄漏源码信息的属性，一律剥离（等价 ProGuard 不 keep 这些 attributes 的行为）。 */
    private static final Set<String> DROPPED = Set.of(
            "LineNumberTable", "LocalVariableTable", "LocalVariableTypeTable",
            "MethodParameters", "SourceDebugExtension");

    /** 常量池条目：按 tag 复用字段，避免为每种常量建一个类。 */
    static final class Cp {
        final int tag;
        String utf8;   // C_UTF8
        long bits;     // C_INT / C_FLOAT / C_LONG / C_DOUBLE 的原始位
        int a;         // 第一个下标；C_METHODHANDLE 时为 reference_kind
        int b;         // 第二个下标；C_METHODHANDLE 时为 reference_index

        Cp(int tag) {
            this.tag = tag;
        }
    }

    static final class Attr {
        int nameIndex;
        byte[] info;

        Attr(int nameIndex, byte[] info) {
            this.nameIndex = nameIndex;
            this.info = info;
        }
    }

    static final class Member {
        int access;
        int nameIndex;
        int descriptorIndex;
        final List<Attr> attributes = new ArrayList<>();

        Member(int access, int nameIndex, int descriptorIndex) {
            this.access = access;
            this.nameIndex = nameIndex;
            this.descriptorIndex = descriptorIndex;
        }
    }

    private int minor;
    private int major;
    /** 下标 0 恒为 null；Long/Double 的第二个槽位为 null 占位。 */
    private final List<Cp> cp = new ArrayList<>();
    private int accessFlags;
    private int thisClass;
    private int superClass;
    private int[] interfaces = new int[0];
    private final List<Member> fields = new ArrayList<>();
    private final List<Member> methods = new ArrayList<>();
    private final List<Attr> attributes = new ArrayList<>();

    private ClassFile() {
        cp.add(null);
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    static ClassFile read(byte[] data) {
        Reader r = new Reader(data);
        ClassFile cf = new ClassFile();
        // u4() 返回 long，MAGIC 是 int：不转一下 0xCAFEBABE 会被符号扩展成负数，永远不相等
        if ((int) r.u4() != MAGIC) {
            throw new IllegalArgumentException("不是 class 文件（magic 不匹配）");
        }
        cf.minor = r.u2();
        cf.major = r.u2();

        // 用 cp.size() 而不是循环变量当游标：Long/Double 占两个槽位，循环变量会与槽位数脱节
        int count = r.u2();
        while (cf.cp.size() < count) {
            Cp c = new Cp(r.u1());
            switch (c.tag) {
                case C_UTF8 -> c.utf8 = new String(r.bytes(r.u2()), StandardCharsets.UTF_8);
                case C_INT, C_FLOAT -> c.bits = r.u4();
                case C_LONG, C_DOUBLE -> {
                    c.bits = r.u8();
                    cf.cp.add(c);
                    cf.cp.add(null); // 双槽占位
                    continue;
                }
                case C_METHODHANDLE -> {
                    c.a = r.u1();
                    c.b = r.u2();
                }
                case C_CLASS, C_STRING, C_METHODTYPE, C_MODULE, C_PACKAGE -> c.a = r.u2();
                case C_FIELDREF, C_METHODREF, C_IMETHODREF, C_NAMEANDTYPE, C_DYNAMIC, C_INVOKEDYNAMIC -> {
                    c.a = r.u2();
                    c.b = r.u2();
                }
                default -> throw new IllegalArgumentException("未知常量池 tag: " + c.tag);
            }
            cf.cp.add(c);
        }

        cf.accessFlags = r.u2();
        cf.thisClass = r.u2();
        cf.superClass = r.u2();
        int ifCount = r.u2();
        cf.interfaces = new int[ifCount];
        for (int i = 0; i < ifCount; i++) {
            cf.interfaces[i] = r.u2();
        }
        cf.readMembers(r, cf.fields);
        cf.readMembers(r, cf.methods);
        cf.readAttributes(r, cf.attributes, false);
        return cf;
    }

    private void readMembers(Reader r, List<Member> out) {
        int count = r.u2();
        for (int i = 0; i < count; i++) {
            Member m = new Member(r.u2(), r.u2(), r.u2());
            readAttributes(r, m.attributes, false);
            out.add(m);
        }
    }

    private void readAttributes(Reader r, List<Attr> out, boolean insideCode) {
        int count = r.u2();
        for (int i = 0; i < count; i++) {
            int nameIndex = r.u2();
            int length = (int) r.u4();
            byte[] info = r.bytes(length);
            String name = utf8(nameIndex);
            if (!insideCode && "Code".equals(name)) {
                out.add(new Attr(nameIndex, filterCode(info)));
                continue;
            }
            if (DROPPED.contains(name)) {
                continue;
            }
            out.add(new Attr(nameIndex, info));
        }
    }

    /** Code 属性本身保留，但它内部的调试属性要摘掉，attributes_count 同步改。 */
    private byte[] filterCode(byte[] info) {
        Reader r = new Reader(info);
        r.skip(4);                                  // max_stack + max_locals
        int codeLength = (int) r.u4();
        int headerLength = 8 + codeLength;
        r.skip(codeLength);
        int exceptionCount = r.u2();
        headerLength += 2 + exceptionCount * 8;
        r.skip(exceptionCount * 8);
        int attrCount = r.u2();

        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        int keptCount = 0;
        for (int i = 0; i < attrCount; i++) {
            int nameIndex = r.u2();
            int length = (int) r.u4();
            byte[] body = r.bytes(length);
            if (DROPPED.contains(utf8(nameIndex))) {
                continue;
            }
            writeU2(kept, nameIndex);
            writeU4(kept, length);
            kept.write(body, 0, body.length);
            keptCount++;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(info, 0, headerLength);
        writeU2(out, keptCount);
        out.write(kept.toByteArray(), 0, kept.size());
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // 写出
    // ------------------------------------------------------------------

    byte[] write() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        writeU4(out, MAGIC);
        writeU2(out, minor);
        writeU2(out, major);
        writeU2(out, cp.size());
        for (int i = 1; i < cp.size(); i++) {
            Cp c = cp.get(i);
            if (c == null) {
                continue; // Long/Double 的占位槽
            }
            out.write(c.tag);
            switch (c.tag) {
                case C_UTF8 -> {
                    byte[] bytes = c.utf8.getBytes(StandardCharsets.UTF_8);
                    writeU2(out, bytes.length);
                    out.write(bytes, 0, bytes.length);
                }
                case C_INT, C_FLOAT -> writeU4(out, c.bits);
                case C_LONG, C_DOUBLE -> writeU8(out, c.bits);
                case C_METHODHANDLE -> {
                    out.write(c.a);
                    writeU2(out, c.b);
                }
                case C_CLASS, C_STRING, C_METHODTYPE, C_MODULE, C_PACKAGE -> writeU2(out, c.a);
                case C_FIELDREF, C_METHODREF, C_IMETHODREF, C_NAMEANDTYPE, C_DYNAMIC, C_INVOKEDYNAMIC -> {
                    writeU2(out, c.a);
                    writeU2(out, c.b);
                }
                default -> throw new IllegalStateException("未知常量池 tag: " + c.tag);
            }
        }
        writeU2(out, accessFlags);
        writeU2(out, thisClass);
        writeU2(out, superClass);
        writeU2(out, interfaces.length);
        for (int i : interfaces) {
            writeU2(out, i);
        }
        writeMembers(out, fields);
        writeMembers(out, methods);
        writeAttributes(out, attributes);
        return out.toByteArray();
    }

    private void writeMembers(ByteArrayOutputStream out, List<Member> members) {
        writeU2(out, members.size());
        for (Member m : members) {
            writeU2(out, m.access);
            writeU2(out, m.nameIndex);
            writeU2(out, m.descriptorIndex);
            writeAttributes(out, m.attributes);
        }
    }

    private void writeAttributes(ByteArrayOutputStream out, List<Attr> attrs) {
        writeU2(out, attrs.size());
        for (Attr a : attrs) {
            writeU2(out, a.nameIndex);
            writeU4(out, a.info.length);
            out.write(a.info, 0, a.info.length);
        }
    }

    // ------------------------------------------------------------------
    // 访问
    // ------------------------------------------------------------------

    /**
     * 把包级私有 / protected 的类与成员放宽到 public。
     *
     * <p>重打包会把类搬进另一个包，而包级私有与 protected 的可见性依赖「同一个运行时包」，
     * 于是「被保留的 apm.ApmCollector 访问同样在 apm 包里、但已被搬走的 Helper」这类调用
     * 会在运行时抛 IllegalAccessError。ProGuard 的 {@code -allowaccessmodification} 干的
     * 就是这件事。private 不动：它只在同一个类或同一个 nest 内可见，类搬到哪都不受影响。</p>
     */
    void widenAccess() {
        accessFlags |= ACC_PUBLIC;
        for (Member m : fields) {
            widen(m);
        }
        for (Member m : methods) {
            widen(m);
        }
    }

    private static void widen(Member m) {
        if ((m.access & ACC_PRIVATE) != 0) {
            return;
        }
        m.access = (m.access & ~ACC_PROTECTED) | ACC_PUBLIC;
    }

    List<Cp> constantPool() {
        return cp;
    }

    int accessFlags() {
        return accessFlags;
    }

    List<Member> fields() {
        return fields;
    }

    List<Member> methods() {
        return methods;
    }

    List<Attr> attributes() {
        return attributes;
    }

    int[] interfaces() {
        return interfaces;
    }

    String utf8(int index) {
        Cp c = cp.get(index);
        return c == null ? null : c.utf8;
    }

    Cp cp(int index) {
        return cp.get(index);
    }

    String className(int index) {
        return index == 0 ? null : utf8(cp.get(index).a);
    }

    String thisName() {
        return className(thisClass);
    }

    String superName() {
        return className(superClass);
    }

    /** 取已有 UTF-8 条目的下标；没有则追加。追加只发生在写的下标上，不影响任何既有下标。 */
    int utf8Index(String value) {
        for (int i = 1; i < cp.size(); i++) {
            Cp c = cp.get(i);
            if (c != null && c.tag == C_UTF8 && value.equals(c.utf8)) {
                return i;
            }
        }
        if (cp.size() >= 0xFFFF) {
            throw new IllegalStateException("常量池条目数超过 u2 上限");
        }
        Cp c = new Cp(C_UTF8);
        c.utf8 = value;
        cp.add(c);
        return cp.size() - 1;
    }

    /**
     * 追加一个 NameAndType 条目并返回其下标。
     *
     * <p>成员改名要用追加而不是就地改：NameAndType 在常量池里是去重共享的，同一条可能同时被
     * 「自研类的引用」「JDK 类的引用」「invokedynamic 的名字」用着，就地改会一起改坏。</p>
     */
    int nameAndTypeIndex(int nameIndex, int descriptorIndex) {
        if (cp.size() >= 0xFFFF) {
            throw new IllegalStateException("常量池条目数超过 u2 上限");
        }
        Cp c = new Cp(C_NAMEANDTYPE);
        c.a = nameIndex;
        c.b = descriptorIndex;
        cp.add(c);
        return cp.size() - 1;
    }

    // ------------------------------------------------------------------
    // 字节读写工具
    // ------------------------------------------------------------------

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

    private static void writeU8(ByteArrayOutputStream out, long v) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((v >>> shift) & 0xFF));
        }
    }

    /** 大端游标；越界直接抛，宁可构建失败也不要产出半截 class 文件。 */
    static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        int u1() {
            check(1);
            return data[pos++] & 0xFF;
        }

        int u2() {
            check(2);
            int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        long u4() {
            check(4);
            long v = ((long) (data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        long u8() {
            long hi = u4();
            long lo = u4();
            return (hi << 32) | lo;
        }

        byte[] bytes(int length) {
            check(length);
            byte[] out = Arrays.copyOfRange(data, pos, pos + length);
            pos += length;
            return out;
        }

        void skip(int length) {
            check(length);
            pos += length;
        }

        private void check(int length) {
            if (length < 0 || pos + length > data.length) {
                throw new IllegalArgumentException("class 文件被截断");
            }
        }
    }
}