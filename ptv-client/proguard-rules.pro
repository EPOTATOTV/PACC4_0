# PACC 玩家端（ptv-client）发行产物混淆配置
# 目标：发行 JAR 反编译后类名/方法名/字段名不可读、类名字符串不可检索、未使用代码被裁剪。
# 说明：本配置只处理「自有代码」，protobuf 运行时与生成类整体保留（其内部依赖描述符与反射）。
#
# 与 CI 的三条硬约束（.github/workflows/ci.yml）绑定，改动前务必对齐：
#   a) com/potatotv/paccclient/PaccClient.class 必须存在（唯一对外入口契约）
#   b) 产物中不得再出现 com/potatotv/paccclient/{detection,transport,signature,store,ops,probe}/
#   c) 必须存在 com/potatotv/paccclient/[a-z]{1,3}.class 短名混淆类
# ---------------------------------------------------------------------------

# JDK 运行时库（Java 9+ 以 jmod 形式提供）：ProGuard 7 不再自动探测，
# 缺少这些库会因「类层次不完整」直接中止。仅按玩家端实际用到的模块引入。
-libraryjars <java.home>/jmods/java.base.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.net.http.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.desktop.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.logging.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.management.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.naming.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.sql.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/java.xml.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/jdk.unsupported.jmod(!**.jar;!module-info.class)
# LocalControlServer 用 com.sun.net.httpserver.*，PaccClient 用 com.sun.management.*：
# 这两类不是 java.* 而属于 jdk.httpserver / jdk.management，漏掉会让 ProGuard 报
# 「unresolved references」并在优化时失去完整类层次信息（少了会保留过多、判错可访问性）。
-libraryjars <java.home>/jmods/jdk.httpserver.jmod(!**.jar;!module-info.class)
-libraryjars <java.home>/jmods/jdk.management.jmod(!**.jar;!module-info.class)

# 保持类名大小写混排，避免大小写不敏感文件系统上互相覆盖
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses

# 混淆 / 收缩 / 优化三级全开（ProGuard 默认即开启，此处只调优化轮次与作用域）
-optimizationpasses 5
-allowaccessmodification
-overloadaggressively

# 控制流相关优化：ProGuard 用「负向过滤器」表达——以 '!' 开头表示「默认全集减去下列项」。
# 这里只关闭两类历史上对 JVM 栈帧/异常表较敏感、且对混淆强度没有正贡献的优化，
# 其余（含 code/simplification/branch、code/removal/conditionals 等分支消解/条件移除）
# 保持默认开启，让控制流被正常化简、合并，反编译后更难还原。
# 注意：开源 ProGuard/R8 不提供真正的控制流平坦化（CFF）——见下方「能力边界」说明。
-optimizations !code/simplification/arithmetic,!code/allocation/variable

# 重打包：把被重命名的自有类全部拍平进 com.potatotv.paccclient 包。
# 该目标包的短名类（com/potatotv/paccclient/a.class…）正是 CI 约束 (c) 的判定依据；
# 同时原业务子包 detection/transport/signature/store/ops/probe 在产物中彻底消失（约束 b）。
-repackageclasses 'com.potatotv.paccclient'

# 类名字符串同步适配：代码中作为字符串常量出现的「被混淆类名」会被一并改写，
# 因此 Class.forName("com.potatotv.paccclient.detection.DetectionEngine") 这类按名查找
# 仍然命中真实类（详见下方 HookDetector 段），静态检索 "com.potatotv.paccclient.xxx" 不再命中。
# 被 -keep 固定名称的类（PaccClient/Json/…）字符串无需改写，保持原样即正确。
-adaptclassstrings
-renamesourcefileattribute SourceFile

# 映射表：把混淆名 → 原名输出到文件，供线上崩溃栈反混淆。
# 该文件是「源码符号表」，只留档到 build 输出目录（target/），绝不进发行 jar / 不上传下载站。
-printmapping target/proguard_map.txt

# ---------------------------------------------------------------------------
# 能力边界（诚实标注，不静默假装已实现）：
# 「控制流平坦化」与「字符串加密」需要商业混淆器（如 DexGuard / 商用加固），
# 开源 ProGuard 与 R8 均不提供这两项能力——R8 的 -obfuscation 也不含 CFF。
# 本工程不引入商业组件，因此这两项在 ProGuard 侧保持「文档化但不启用」。
# 其中「字符串加密」由 platform/string-protect 模块以构建期生成 + 运行时解密的方式补齐
# （见本文件末尾的可选 keep 段与 ptv-client/pom.xml 的 opt-in profile），它不是 ProGuard 能力。
# ---------------------------------------------------------------------------

# 运行时保留注解与泛型签名（JDK 内部、序列化路径与 protobuf 反射使用）
# InnerClasses/EnclosingMethod 必须保留：Class.getEnclosingClass()/内部类引用依赖它们，
# 被剥离后反序列化与反射解析内部类会抛异常。
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 入口：唯一对外契约，必须保留原名与签名
-keep public class com.potatotv.paccclient.PaccClient {
    public static void main(java.lang.String[]);
}

# JNI native 方法：native 方法由 JVM 按「类名 + 方法名 + 描述符」符号查找，
# 任一被改名/改描述符都会导致 UnsatisfiedLinkError（本模块当前无 native 方法，
# 此处是安全网，规则本身对无 native 的工程是无操作）。
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# java.io.Serializable 成员：序列化按字段名与签名往返，字段被改名/裁剪会破坏兼容性。
# 本项目自有代码未实现 Serializable（protobuf 生成类已整体 keep），此处为通用安全网。
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# protobuf 生成类（PaccWire）依赖 GeneratedMessageV3 的描述符与默认实例反射，整体保留。
# 其内部字段编号由字节码中的 descriptor 数据驱动，保留即可保证 WSS 线上编解码不变。
-keep class com.potatotv.pacc.proto.** { *; }
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**

# ---------------------------------------------------------------------------
# v5.4 APM / 安全模块 keep 策略
# 刻意不使用 `-keep class com.potatotv.paccclient.apm.** { public *; }` 这类整包 keep：
# 它会连带保留内部实现类，直接抵消收缩与重打包的效果。这里只固定「反射引用」与
# 「跨模块/后续由检测引擎按名调用」的最小集合。
# ---------------------------------------------------------------------------

# HookDetector 通过 Class.forName 校验「关键类是否被影子类前置」。它引用的三个类名
# 以字符串常量形式出现（security/HookDetector.java 的 CRITICAL_CLASSES）：
#   com.potatotv.paccclient.Json / PaccClient / detection.DetectionEngine
# Json 与 PaccClient 已由上方 -keep 固定原名，字符串天然命中；
# DetectionEngine 不固定名称，改由 -adaptclassstrings 把该字符串同步改写为新类名，
# 因此 Class.forName 仍装载到同一类，且 detection/ 包不再残留在产物中（CI 约束 b）。
# 切勿给它加 -keepnames：一旦固定名字，重打包失效，约束 b 直接失败。
-keep class com.potatotv.paccclient.Json { *; }

# APM 对外入口与公开插桩 sink：DetectionMetrics.SINK 由检测引擎（后续接入）按名调用，
# ApmCollector / ClientHealthMetrics 是外部按名引用的入口，重命名会导致后续集成断链。
-keep class com.potatotv.paccclient.apm.ApmCollector { public *; }
-keep class com.potatotv.paccclient.apm.DetectionMetrics$Sink { public *; }
-keep class com.potatotv.paccclient.apm.ClientHealthMetrics { public *; }

# 安全上报入口与其函数式接口：由 PaccClient 以外的地方（测试/后续原生层）按名调用
-keep class com.potatotv.paccclient.security.SecurityReporter { public *; }
-keep class com.potatotv.paccclient.security.SecurityReporter$BatchSink { *; }
-keep class com.potatotv.paccclient.security.SecurityReporter$AttestationTransport { *; }
-keep class com.potatotv.paccclient.security.SecurityReporter$Challenge { *; }

# ---------------------------------------------------------------------------
# 字符串加密（platform/string-protect）产物 keep：
# 仅当以 -Dpacc.stringprotect.enabled=true 打开 opt-in profile 时，构建会生成
# com.potatotv.paccclient.protect.PaccSecretStrings（端到端加密的端点/密钥键字面量）。
# 它当前无源码引用（消费方改造属后续客户端源码变更），若不 keep 会被收缩阶段整类删除，
# 使加固失效；类名不在 CI 约束 (b) 的包列表内，keep 不改动约束判定。
# 默认构建不生成该类，这条规则无匹配对象（配合 -ignorewarnings 不会致失败）。
# ---------------------------------------------------------------------------
-keep class com.potatotv.paccclient.protect.PaccSecretStrings { public static *; }

# 资源文件原样保留（pacc-client.properties 由 ClientConfig 按路径读取）
-keepdirectories

# 库依赖引用缺失只告警不中断（protobuf 作为 library jar 提供，JDK 模块类由 ProGuard 自动识别）
-ignorewarnings
-verbose