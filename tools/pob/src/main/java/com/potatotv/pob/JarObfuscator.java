package com.potatotv.pob;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 混淆流水线：读入主 jar（含资源）与依赖 jar（只取类），统一重命名后写回单文件产物。
 *
 * <p>产物与 ProGuard 时期的形态保持一致：<b>不写目录条目</b>。这一点是硬要求——写目录条目会把
 * {@code com/potatotv/paccclient/detection/} 这类空目录留在包里，CI 的「原始业务包必须消失」
 * 断言会因此判红。</p>
 */
final class JarObfuscator {

    private static final String SIGNATURE_PREFIX = "META-INF/";
    private static final Set<String> SIGNATURE_SUFFIXES = Set.of(".SF", ".RSA", ".DSA", ".EC");

    private final Path mappingFile;
    private final String targetPackage;

    /** 主 jar 的资源条目（类之外的一切，保持原字节）。 */
    private final Map<String, byte[]> resources = new LinkedHashMap<>();
    private final List<ClassFile> classes = new ArrayList<>();
    private final Set<String> seenClassNames = new LinkedHashSet<>();

    private Renamer renamer;
    private NameRewriter nameRewriter;

    JarObfuscator(Path mappingFile, String targetPackage) {
        this.mappingFile = mappingFile;
        this.targetPackage = targetPackage;
    }

    /** 本次重命名结果，供测试与 retrace 使用；必须在 {@link #run} 之后调用。 */
    Renamer renamer() {
        return renamer;
    }

    void run(Path inputJar, List<Path> mergedJars, PobRules rules) throws IOException {
        readJar(inputJar, true);
        for (Path lib : mergedJars) {
            readJar(lib, false);
        }
        renamer = Renamer.compute(classes, rules, targetPackage);
        nameRewriter = new NameRewriter(renamer.classMap());

        List<Named> ordered = new ArrayList<>();
        for (ClassFile cf : classes) {
            String original = cf.thisName(); // rewriteUtf8 之后 thisName() 已经是新名，必须先取
            if (renamer.covers(original)) {
                cf.widenAccess();
            }
            rename(cf);
            ordered.add(new Named(cf, original));
        }

        byte[] vault = rules.encryptStrings()
                ? new StringEncryptor(targetPackage, rules, renamer).encrypt(classes) : null;

        if (rules.bogusCode()) {
            for (Named n : ordered) {
                if (rules.enhancesClass(n.original)) {
                    BogusInsert.apply(n.cf);
                }
            }
        }
        if (rules.flatten()) {
            System.err.println("POB：控制流平坦化需要生成 StackMapTable frame，当前未实现，已忽略 flatten");
        }

        byte[] guard = null;
        if (rules.integrity()) {
            List<ClassFile> marked = new ArrayList<>();
            for (Named n : ordered) {
                if (rules.enhancesClass(n.original)) {
                    marked.add(n.cf);
                }
            }
            guard = new IntegrityGuardInjector(targetPackage).inject(marked);
        }

        Map<String, byte[]> output = new LinkedHashMap<>();
        byte[] manifest = resources.get("META-INF/MANIFEST.MF");
        if (manifest != null) {
            output.put("META-INF/MANIFEST.MF", manifest);
        }
        for (Map.Entry<String, byte[]> e : resources.entrySet()) {
            if (!e.getKey().equals("META-INF/MANIFEST.MF")) {
                output.put(e.getKey(), e.getValue());
            }
        }
        for (Named n : ordered) {
            output.put(n.cf.thisName() + ".class", n.cf.write());
        }
        if (vault != null) {
            output.put(targetPackage + '/' + VaultNames.STRING_VAULT_SIMPLE + ".class", vault);
        }
        if (guard != null) {
            output.put(targetPackage + '/' + VaultNames.INTEGRITY_GUARD_SIMPLE + ".class", guard);
        }

        writeJar(inputJar, output);
        writeMapping();
    }

    /** 记住重命名前的原名，供 enhance / keep 之类的规则在改名后仍能命中。 */
    private static final class Named {
        final ClassFile cf;
        final String original;

        Named(ClassFile cf, String original) {
            this.cf = cf;
            this.original = original;
        }
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    private void readJar(Path jar, boolean main) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name.endsWith("/")) {
                    continue;
                }
                byte[] bytes = in.readAllBytes();
                if (name.endsWith(".class")) {
                    if (!main && "module-info.class".equals(name)) {
                        continue;
                    }
                    ClassFile cf;
                    try {
                        cf = ClassFile.read(bytes);
                    } catch (IllegalArgumentException e) {
                        throw new IOException(jar + " 的 " + name + " 不是合法 class 文件："
                                + e.getMessage(), e);
                    }
                    String className = cf.thisName();
                    if (className == null || !seenClassNames.add(className)) {
                        continue;
                    }
                    classes.add(cf);
                    continue;
                }
                if (!main) {
                    continue; // 依赖只并入类，资源以主 jar 为准
                }
                if (isSignature(name) || isMavenMetadata(name)) {
                    continue;
                }
                resources.put(name, bytes);
            }
        }
    }

    private static boolean isSignature(String name) {
        if (!name.toUpperCase(Locale.ROOT).startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        for (String suffix : SIGNATURE_SUFFIXES) {
            if (upper.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMavenMetadata(String name) {
        return name.startsWith("META-INF/maven/");
    }

    // ------------------------------------------------------------------
    // 改写
    // ------------------------------------------------------------------

    private void rename(ClassFile cf) {
        // 顺序不能换：前三步按「原名」查映射表，rewriteUtf8 一旦跑过，常量池里就只剩新名了
        applyMemberNames(cf);
        applyEnclosingMethod(cf);
        applyInvokeDynamicSam(cf);
        applyInnerClassNames(cf);
        rewriteUtf8(cf);
        applySourceFile(cf);
    }

    /** 成员名改写：常量池里的引用走 {@link #remapReference}，类自身的声明直接改 name_index。 */
    private void applyMemberNames(ClassFile cf) {
        Map<String, Integer> renamed = new HashMap<>();
        List<ClassFile.Cp> cp = cf.constantPool();
        for (int i = 1; i < cp.size(); i++) {
            ClassFile.Cp c = cp.get(i);
            if (c != null && (c.tag == ClassFile.C_FIELDREF || c.tag == ClassFile.C_METHODREF
                    || c.tag == ClassFile.C_IMETHODREF)) {
                remapReference(cf, c, renamed);
            }
        }
        for (ClassFile.Member m : cf.fields()) {
            remapMember(cf, m, renamer.fieldMap());
        }
        for (ClassFile.Member m : cf.methods()) {
            remapMember(cf, m, renamer.methodMap());
        }
    }

    /**
     * 把一条 Fieldref / Methodref / InterfaceMethodref 的名字换成新名。
     *
     * <p>映射是按「名字 + 描述符」全局分配的，但这里<b>不能</b>只看名字和描述符：同一组
     * (名字, 描述符) 完全可能既被自研类引用、又被 JDK / 第三方类引用。自研代码里只要有一个
     * {@code load(InputStream)V}，{@code java/util/Properties.load(InputStream)V} 就会命中同一条
     * 映射，改了下场就是运行时 {@code NoSuchMethodError}。所以逐个引用判断：只有该成员确实
     * 解析到自研命名空间内的声明时，引用才跟着改。</p>
     *
     * <p>另外 NameAndType 是常量池里去重共享的条目，就地改会连累别的引用（外部类引用、
     * invokedynamic 的名字），因此改名时新建一条并只让本引用指过去。</p>
     */
    private void remapReference(ClassFile cf, ClassFile.Cp ref, Map<String, Integer> renamed) {
        String owner = cf.className(ref.a);
        if (owner == null || !renamer.covers(owner)) {
            return; // 外部类（JDK / 第三方）的成员名不能动
        }
        ClassFile.Cp nameAndType = cf.cp(ref.b);
        String name = cf.utf8(nameAndType.a);
        String desc = cf.utf8(nameAndType.b);
        String key = Renamer.key(name, desc);
        String mapped = memberMap(desc).get(key);
        if (mapped == null || mapped.equals(name)) {
            return;
        }
        // owner 是我们这边的类，但成员可能是从外部父类/接口继承来的——那种声明没被改过名字
        if (!resolvesInScope(owner, name, desc)) {
            return;
        }
        ref.b = renamed.computeIfAbsent(key,
                k -> cf.nameAndTypeIndex(cf.utf8Index(mapped), cf.utf8Index(desc)));
    }

    /** 描述符以 '(' 开头的是方法，否则是字段。 */
    private Map<String, String> memberMap(String desc) {
        return desc.startsWith("(") ? renamer.methodMap() : renamer.fieldMap();
    }

    /**
     * 从 owner 起沿父类链与接口闭包找该成员的声明。
     *
     * <p>只在自研命名空间内找，碰到外部类即停：命中的声明必然是被我们改过名（或整体放弃）的那个，
     * 于是「声明改名了、引用没改」这种断链不会发生。反过来说，如果一路走到外部类都没找到，
     * 说明真正生效的是外部声明，名字原样。</p>
     */
    private boolean resolvesInScope(String type, String name, String desc) {
        return resolvesInScope(type, name, desc, new HashSet<>());
    }

    private boolean resolvesInScope(String type, String name, String desc, Set<String> visited) {
        if (type == null || !visited.add(type)) {
            return false;
        }
        Renamer.ClassInfo info = renamer.classInfo(type);
        if (info == null) {
            return false; // 外部声明
        }
        for (Renamer.MemberInfo m : info.methods) {
            if (name.equals(m.name) && desc.equals(m.desc)) {
                return true;
            }
        }
        for (Renamer.MemberInfo f : info.fields) {
            if (name.equals(f.name) && desc.equals(f.desc)) {
                return true;
            }
        }
        if (resolvesInScope(info.superName, name, desc, visited)) {
            return true;
        }
        for (String itf : info.interfaces) {
            if (resolvesInScope(itf, name, desc, visited)) {
                return true;
            }
        }
        return false;
    }

    private void remapMember(ClassFile cf, ClassFile.Member member, Map<String, String> map) {
        String name = cf.utf8(member.nameIndex);
        String desc = cf.utf8(member.descriptorIndex);
        String mapped = map.get(Renamer.key(name, desc));
        if (mapped != null) {
            member.nameIndex = cf.utf8Index(mapped);
        }
    }

    /**
     * 局部类/匿名类的 EnclosingMethod 指向的 NameAndType 不被任何 Methodref 引用，要单独处理。
     * 判定跟成员引用一致：外层类得在自研命名空间内，且改的名字确实对应它自己的声明。
     */
    private void applyEnclosingMethod(ClassFile cf) {
        for (ClassFile.Attr attr : cf.attributes()) {
            if (!"EnclosingMethod".equals(cf.utf8(attr.nameIndex)) || attr.info.length != 4) {
                continue;
            }
            int methodIndex = u2At(attr.info, 2);
            if (methodIndex == 0) {
                continue;
            }
            String owner = cf.className(u2At(attr.info, 0));
            ClassFile.Cp nameAndType = cf.cp(methodIndex);
            String name = cf.utf8(nameAndType.a);
            String desc = cf.utf8(nameAndType.b);
            String mapped = memberMap(desc).get(Renamer.key(name, desc));
            if (owner == null || mapped == null || mapped.equals(name)
                    || !renamer.covers(owner) || !resolvesInScope(owner, name, desc)) {
                continue;
            }
            putU2(attr.info, 2, cf.nameAndTypeIndex(cf.utf8Index(mapped), cf.utf8Index(desc)));
        }
    }

    /**
     * lambda / 方法引用经 invokedynamic 链接，其名字必须是函数式接口 SAM 的名字。
     * 若该 SAM 属于被混淆内部类型，按 SAM 的描述符反查映射改名。
     *
     * <p>同样要新建 NameAndType 而不是就地改：indy 的 NameAndType 常与调用同一方法的
     * Methodref 共享（两边名字和描述符完全一样），就地改会把普通调用一起改坏。</p>
     */
    private void applyInvokeDynamicSam(ClassFile cf) {
        // 必须按下标遍历：改名字会往常量池追加条目，for-each 会 ConcurrentModificationException
        List<ClassFile.Cp> cp = cf.constantPool();
        for (int i = 1; i < cp.size(); i++) {
            ClassFile.Cp c = cp.get(i);
            if (c == null || c.tag != ClassFile.C_INVOKEDYNAMIC) {
                continue;
            }
            ClassFile.Cp nameAndType = cf.cp(c.b);
            String name = cf.utf8(nameAndType.a);
            String desc = cf.utf8(nameAndType.b);
            String iface = NameRewriter.returnTypeOf(desc);
            if (iface == null) {
                continue;
            }
            String samDesc = findSamDescriptor(iface, name);
            if (samDesc == null) {
                continue;
            }
            String mapped = renamer.methodMap().get(Renamer.key(name, samDesc));
            if (mapped != null && !mapped.equals(name)) {
                // 只换名字：描述符必须保持 callsite 的那个（返回函数式接口），不是 SAM 的签名
                c.b = cf.nameAndTypeIndex(cf.utf8Index(mapped), cf.utf8Index(desc));
            }
        }
    }

    private String findSamDescriptor(String type, String samName) {
        Map<String, String> found = new LinkedHashMap<>();
        collectAbstract(type, samName, found, new LinkedHashSet<>());
        return found.size() == 1 ? found.values().iterator().next() : null;
    }

    private void collectAbstract(String type, String samName, Map<String, String> found, Set<String> visited) {
        if (type == null || !visited.add(type)) {
            return;
        }
        Renamer.ClassInfo info = renamer.classInfo(type);
        if (info == null) {
            return;
        }
        for (Renamer.MemberInfo m : info.methods) {
            if (samName.equals(m.name) && (m.access & ClassFile.ACC_ABSTRACT) != 0) {
                found.put(m.desc, m.desc);
            }
        }
        collectAbstract(info.superName, samName, found, visited);
        for (String itf : info.interfaces) {
            collectAbstract(itf, samName, found, visited);
        }
    }

    /** 类名/描述符/签名的原地改写，覆盖面等价于 ProGuard 的 -adaptclassstrings。 */
    private void rewriteUtf8(ClassFile cf) {
        for (int i = 1; i < cf.constantPool().size(); i++) {
            ClassFile.Cp c = cf.constantPool().get(i);
            if (c != null && c.tag == ClassFile.C_UTF8) {
                c.utf8 = nameRewriter.rewrite(c.utf8);
            }
        }
    }

    /** InnerClasses 的 inner_name_index 是「简单名」，不是类名，得按新类名单独对齐。 */
    private void applyInnerClassNames(ClassFile cf) {
        for (ClassFile.Attr attr : cf.attributes()) {
            if (!"InnerClasses".equals(cf.utf8(attr.nameIndex))) {
                continue;
            }
            int count = u2At(attr.info, 0);
            for (int i = 0; i < count; i++) {
                int base = 2 + i * 8;
                int innerClassIndex = u2At(attr.info, base);
                int innerNameIndex = u2At(attr.info, base + 4);
                if (innerNameIndex == 0) {
                    continue;
                }
                String renamed = renamer.classMap().get(cf.className(innerClassIndex));
                if (renamed == null) {
                    continue;
                }
                int slash = renamed.lastIndexOf('/');
                String simple = slash < 0 ? renamed : renamed.substring(slash + 1);
                putU2(attr.info, base + 4, cf.utf8Index(simple));
            }
        }
    }

    /** SourceFile 统一改名为字面量 SourceFile，原文（含 .java 文件名）不再泄漏。 */
    private void applySourceFile(ClassFile cf) {
        for (ClassFile.Attr attr : cf.attributes()) {
            if ("SourceFile".equals(cf.utf8(attr.nameIndex)) && attr.info.length == 2) {
                putU2(attr.info, 0, cf.utf8Index("SourceFile"));
            }
        }
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    private void writeJar(Path target, Map<String, byte[]> output) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 20);
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, byte[]> e : output.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        // 读完全部输入后才落盘，因此可以安全地原地覆盖（与 ProGuard 时期的行为一致）
        Path tmp = target.resolveSibling(target.getFileName() + ".pob-tmp");
        Files.write(tmp, buffer.toByteArray());
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeMapping() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# PACC POB mapping（原名 -> 混淆名，仅供崩溃栈还原，不得随发行包发布）\n");
        sb.append("# 与 ProGuard 的 -printmapping 方向一致；被 keep 的原样成员不出现在这里。\n");
        Map<String, String> classes = new TreeMap<>(renamer.classMap());
        for (Map.Entry<String, String> e : classes.entrySet()) {
            sb.append(e.getKey().replace('/', '.')).append(" -> ")
                    .append(e.getValue().replace('/', '.')).append('\n');
        }
        appendMembers(sb, renamer.methodMap(), "方法");
        appendMembers(sb, renamer.fieldMap(), "字段");
        if (mappingFile.getParent() != null) {
            Files.createDirectories(mappingFile.getParent());
        }
        Files.writeString(mappingFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private void appendMembers(StringBuilder sb, Map<String, String> map, String label) {
        List<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);
        sb.append("\n# 成员（").append(label).append("）：原名 + 描述符 -> 混淆名\n");
        for (String k : keys) {
            int sep = k.indexOf('\0');
            sb.append("  ").append(k, 0, sep).append(' ').append(k.substring(sep + 1))
                    .append(" -> ").append(map.get(k)).append('\n');
        }
    }

    static int u2At(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static void putU2(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }
}