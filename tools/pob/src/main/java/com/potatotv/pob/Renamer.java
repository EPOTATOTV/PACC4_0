package com.potatotv.pob;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 重命名映射的计算。整个混淆只有这一处「策略」，其余步骤都是按映射机械改写。
 *
 * <p>方法名/字段名采用<b>按签名（名字 + 描述符）全局分配</b>的策略，而不是按类分配。
 * 原因：以容器的整套类是同时处理的，同名同描述符的方法必然属于同一条虚方法链
 * （override、接口实现、lambda 的 SAM），给同一个键分配同一个新名，
 * 这些关系就自动保持一致，不需要额外做类层次分析。</p>
 *
 * <p>安全性来自两条硬约束：</p>
 * <ol>
 *   <li>只要某个声明<b>不能</b>改（保留类成员、外部类的覆写、枚举/记录、native…），
 *       该签名就整体放弃重命名——宁可少混淆，不能出现「一半改了、一半没改」；</li>
 *   <li>新旧名字一一对应（双射），且新名避开所有保留下来的成员名，
 *       因此不会出现「两个不同方法被改成同名」这种改变虚分派语义的情况。</li>
 * </ol>
 */
final class Renamer {

    /** 视为「中性根」的外部类型：链到它们为止，不再当作外部祖先。 */
    private static final Set<String> NEUTRAL_ROOTS = Set.of(
            "java/lang/Object", "java/lang/Enum", "java/lang/Record");

    /** 参与混淆的自研命名空间。列表之外的一律不碰。 */
    private static final List<String> OBFUSCATABLE_PREFIXES = List.of(
            "com/potatotv/paccclient/", "com/potatotv/pbp/",
            "com/potatotv/prl/", "com/potatotv/pcu/");

    /** JVM / 序列化按名字回调的方法，改了名字就会失效。 */
    private static final Set<String> RESERVED_METHODS = Set.of(
            "getClass()Ljava/lang/Class;",
            "hashCode()I",
            "equals(Ljava/lang/Object;)Z",
            "clone()Ljava/lang/Object;",
            "toString()Ljava/lang/String;",
            "notify()V",
            "notifyAll()V",
            "wait()V",
            "wait(J)V",
            "wait(JI)V",
            "finalize()V",
            "name()Ljava/lang/String;",
            "ordinal()I",
            "compareTo(Ljava/lang/Enum;)I",
            "getDeclaringClass()Ljava/lang/Class;",
            "describeConstable()Ljava/util/Optional;",
            "readObject(Ljava/io/ObjectInputStream;)V",
            "writeObject(Ljava/io/ObjectOutputStream;)V",
            "readObjectNoData()V",
            "readResolve()Ljava/lang/Object;",
            "writeReplace()Ljava/lang/Object;");

    private static final Set<String> RESERVED_FIELDS = Set.of(
            "serialVersionUID", "serialPersistentFields");

    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";

    static final class MemberInfo {
        final String name;
        final String desc;
        final int access;

        MemberInfo(String name, String desc, int access) {
            this.name = name;
            this.desc = desc;
            this.access = access;
        }
    }

    static final class ClassInfo {
        final String name;
        String superName;
        /** 嵌套类的外层类名；顶层类为 null。见 {@link #linkNested()}。 */
        String outerName;
        List<String> interfaces = new ArrayList<>();
        int access;
        final List<MemberInfo> fields = new ArrayList<>();
        final List<MemberInfo> methods = new ArrayList<>();

        ClassInfo(String name) {
            this.name = name;
        }
    }

    private final Map<String, String> classMap = new LinkedHashMap<>();
    private final Map<String, String> methodMap = new HashMap<>();
    private final Map<String, String> fieldMap = new HashMap<>();
    private final Map<String, ClassInfo> infos = new LinkedHashMap<>();
    private final Map<String, Boolean> externalAncestorMemo = new HashMap<>();
    private final Set<String> reservedNames = new HashSet<>();

    static Renamer compute(List<ClassFile> classes, PobRules rules, String targetPackage) {
        Renamer renamer = new Renamer();
        renamer.index(classes);
        renamer.assignClasses(rules, targetPackage);
        renamer.assignMembers(rules);
        return renamer;
    }

    /** 该类是否属于 POB 的处理范围（自研命名空间之内）。 */
    boolean covers(String internalName) {
        return isObfuscatable(internalName);
    }

    Map<String, String> classMap() {
        return classMap;
    }

    ClassInfo classInfo(String internalName) {
        return infos.get(internalName);
    }

    Map<String, String> methodMap() {
        return methodMap;
    }

    Map<String, String> fieldMap() {
        return fieldMap;
    }

    static String key(String name, String desc) {
        return name + '\0' + desc;
    }

    // ------------------------------------------------------------------

    private void index(List<ClassFile> classes) {
        for (ClassFile cf : classes) {
            String name = cf.thisName();
            if (name == null || name.equals("module-info")) {
                continue;
            }
            ClassInfo info = new ClassInfo(name);
            info.superName = cf.superName();
            info.access = cf.accessFlags();
            for (int i : cf.interfaces()) {
                info.interfaces.add(cf.className(i));
            }
            for (ClassFile.Member m : cf.fields()) {
                info.fields.add(new MemberInfo(cf.utf8(m.nameIndex), cf.utf8(m.descriptorIndex), m.access));
            }
            for (ClassFile.Member m : cf.methods()) {
                info.methods.add(new MemberInfo(cf.utf8(m.nameIndex), cf.utf8(m.descriptorIndex), m.access));
            }
            infos.put(name, info);
        }
        linkNested();
    }

    /**
     * 认外层类：从名字里最后一个 {@code $} 往左找，取第一个确实存在于输入集合里的前缀。
     * 不能用「含 $ 就是嵌套类」判断——顶层类名里带 $ 是合法的，那会被误当成嵌套类搬进
     * 默认包。
     */
    private void linkNested() {
        for (ClassInfo info : infos.values()) {
            for (int i = info.name.lastIndexOf('$'); i > 0; i = info.name.lastIndexOf('$', i - 1)) {
                String candidate = info.name.substring(0, i);
                if (infos.containsKey(candidate)) {
                    info.outerName = candidate;
                    break;
                }
            }
        }
    }

    private void assignClasses(PobRules rules, String targetPackage) {
        Set<String> usedSimpleNames = new HashSet<>();
        for (String kept : rules.keptClassNames()) {
            int slash = kept.lastIndexOf('/');
            usedSimpleNames.add(slash < 0 ? kept : kept.substring(slash + 1));
        }
        List<String> topLevel = new ArrayList<>();
        List<String> nested = new ArrayList<>();
        for (ClassInfo info : infos.values()) {
            if (isObfuscatable(info.name) && !rules.keepsClass(info.name)) {
                (info.outerName == null ? topLevel : nested).add(info.name);
            }
        }
        Collections.sort(topLevel);
        // 字典序天然保证外层排在嵌套类前面（"A$B" < "A$B$C"），下面可以直接查外层的分配结果
        Collections.sort(nested);

        int counter = 0;
        for (String name : topLevel) {
            String shortName;
            do {
                shortName = shortName(counter++);
            } while (usedSimpleNames.contains(shortName));
            classMap.put(name, targetPackage + '/' + shortName);
        }
        // 嵌套类跟着外层落在同一个包里，而不是各自平铺到 targetPackage。JVMS 要求 nest host
        // 与 nest member 处于同一个运行时包：外层被 keep（没搬走）时如果把成员单独搬走，
        // 成员访问外层的 private 成员会直接 LinkageError。
        for (String name : nested) {
            String outer = infos.get(name).outerName;
            classMap.put(name, classMap.getOrDefault(outer, outer) + '$' + shortName(counter++));
        }
    }

    private void assignMembers(PobRules rules) {
        // 「可改」与「不可改」按签名分别收集：只要某个签名在任意一处不能改，该签名就整体
        // 放弃重命名。否则会出现「接口方法还叫 greet、实现类已改成 a」的一半改一半没改，
        // 覆写关系断开，运行时直接 AbstractMethodError。
        Set<String> methodKeys = new TreeSet<>();
        Set<String> blockedMethods = new HashSet<>();
        Set<String> fieldKeys = new TreeSet<>();
        Set<String> blockedFields = new HashSet<>();

        for (ClassInfo info : infos.values()) {
            boolean renamable = isObfuscatable(info.name);
            boolean enumOrRecord = isEnumOrRecord(info);
            boolean externalAncestor = hasExternalAncestor(info.name);

            for (MemberInfo f : info.fields) {
                boolean eligible = renamable && !enumOrRecord && !RESERVED_FIELDS.contains(f.name)
                        && !rules.keepsMember(info.name, f.name, f.access);
                record(fieldKeys, blockedFields, key(f.name, f.desc), f.name, eligible);
            }
            for (MemberInfo m : info.methods) {
                boolean eligible = renamable && !enumOrRecord
                        && !m.name.startsWith("<")
                        && (m.access & ClassFile.ACC_NATIVE) == 0
                        && !RESERVED_METHODS.contains(m.name + m.desc)
                        && !rules.keepsMember(info.name, m.name, m.access)
                        // 有外部祖先的类，非私有非静态方法可能是别人的覆写，名字不能动
                        && (!externalAncestor
                            || (m.access & (ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC)) != 0);
                record(methodKeys, blockedMethods, key(m.name, m.desc), m.name, eligible);
            }
        }

        int counter = 0;
        for (String k : methodKeys) {
            if (!blockedMethods.contains(k)) {
                counter = assign(methodMap, k, counter);
            }
        }
        for (String k : fieldKeys) {
            if (!blockedFields.contains(k)) {
                counter = assign(fieldMap, k, counter);
            }
        }
    }

    private void record(Set<String> eligible, Set<String> blocked, String memberKey, String name,
                        boolean canRename) {
        if (canRename) {
            eligible.add(memberKey);
        } else {
            blocked.add(memberKey);
        }
        if (!canRename) {
            reservedNames.add(name); // 新名不得与任何保留下来的成员名撞车
        }
    }

    /**
     * 枚举与记录类的成员整体不重命名。枚举的 {@code values()} 由
     * {@code Class.getEnumConstantsShared()} 反射调用；记录的访问器由
     * {@code getRecordComponents()[i].getAccessor()} 按组件名查找。
     *
     * <p>记录类不能作为 access_flags 里的位来判断（JVMS 的 access_flags 是 u2，
     * ASM 的 {@code ACC_RECORD=0x10000} 是伪标志），靠的是它直接继承
     * {@code java/lang/Record} 且记录类不可再被继承这一事实。</p>
     */
    private static boolean isEnumOrRecord(ClassInfo info) {
        return (info.access & ClassFile.ACC_ENUM) != 0 || "java/lang/Record".equals(info.superName);
    }

    private int assign(Map<String, String> map, String k, int counter) {
        String candidate;
        do {
            if (counter >= 18278) {
                throw new IllegalStateException("需要重命名的成员超过短名池容量");
            }
            candidate = shortName(counter++);
        } while (reservedNames.contains(candidate));
        reservedNames.add(candidate);
        map.put(k, candidate);
        return counter;
    }

    private boolean hasExternalAncestor(String name) {
        Boolean cached = externalAncestorMemo.get(name);
        if (cached != null) {
            return cached;
        }
        ClassInfo info = infos.get(name);
        if (info == null) {
            return true;
        }
        externalAncestorMemo.put(name, Boolean.FALSE); // 防环
        boolean external = false;
        String superName = info.superName;
        if (superName != null && !NEUTRAL_ROOTS.contains(superName)) {
            external = hasExternalAncestor(superName);
        }
        if (!external) {
            for (String itf : info.interfaces) {
                if (itf == null || NEUTRAL_ROOTS.contains(itf)) {
                    continue;
                }
                if (hasExternalAncestor(itf)) {
                    external = true;
                    break;
                }
            }
        }
        externalAncestorMemo.put(name, external);
        return external;
    }

    private static boolean isObfuscatable(String name) {
        for (String prefix : OBFUSCATABLE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 短名池：a…z、aa…zz、aaa…（上限 18278，刚好是 [a-z]{1,3} 的全集）。 */
    static String shortName(int index) {
        int i = index;
        if (i < 26) {
            return String.valueOf(LETTERS.charAt(i));
        }
        i -= 26;
        if (i < 676) {
            return "" + LETTERS.charAt(i / 26) + LETTERS.charAt(i % 26);
        }
        i -= 676;
        if (i < 17576) {
            return "" + LETTERS.charAt(i / 676) + LETTERS.charAt(i / 26 % 26) + LETTERS.charAt(i % 26);
        }
        throw new IllegalStateException("短名池耗尽");
    }
}