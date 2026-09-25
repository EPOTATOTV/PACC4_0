# PACC 玩家端（ptv-client）发行产物混淆配置
# 目标：发行 JAR 反编译后类名/方法名/字段名不可读、类名字符串不可检索、未使用代码被裁剪。
# 说明：本配置只处理「自有代码」，protobuf 运行时与生成类整体保留（其内部依赖描述符与反射）。
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

# 保持类名大小写混排，避免大小写不敏感文件系统上互相覆盖
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses

# 混淆 / 收缩 / 优化三级全开（ProGuard 默认即开启，此处只调优化轮次与作用域）
-optimizationpasses 5
-allowaccessmodification
-overloadaggressively
# 重打包到与业务无关的包名下，降低按包名定位模块结构的可读性
-repackageclasses 'com.potatotv.pacc.a'

# 类名字符串同步适配：代码中以字符串出现的类名会被一并改写，
# 静态检索 "com.potatotv.paccclient.xxx" 不再命中；同时隐藏原始源文件名。
# 注意：被 -keepname/-keep 固定的类名不会被改写（见下方安全模块 keep 段）。
-adaptclassstrings
-renamesourcefileattribute SourceFile

# 说明（诚实标注，不静默假装已实现）：
# 「控制流平坦化」与「字符串加密」需要商业混淆器（如 DexGuard / 商用加固），开源 ProGuard 与 R8
# 均不提供这两项能力——R8 的 -obfuscation 也不含 CFF。本工程不引入商业组件，因此这两项保持
# 文档化但不启用；如需启用请替换打包插件并在此处补充对应规则，切勿写成已启用的假配置。

# 运行时保留注解与泛型签名（JDK 内部与序列化路径使用）
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 入口：唯一对外契约，必须保留原名与签名
-keep public class com.potatotv.paccclient.PaccClient {
    public static void main(java.lang.String[]);
}

# protobuf 生成类（PaccWire）依赖 GeneratedMessageV3 的描述符与默认实例反射，整体保留
-keep class com.potatotv.pacc.proto.** { *; }
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**

# ---------------------------------------------------------------------------
# v5.4 APM / 安全模块 keep 策略
# 刻意不使用 `-keep class com.potatotv.paccclient.apm.** { public *; }` 这类整包 keep：
# 它会连带保留内部实现类，直接抵消收缩与重打包的效果。这里只固定「反射引用」与
# 「跨模块/后续由检测引擎按名调用」的最小集合。
# ---------------------------------------------------------------------------

# HookDetector 通过 Class.forName 校验「关键类是否被影子类前置」，
# 这些类名以字符串出现在代码里，一旦被重命名，校验目标就会错位（漏报）。
# （PaccClient 已在上方 -keep 固定名称，此处只需补 DetectionEngine。）
-keepnames class com.potatotv.paccclient.detection.DetectionEngine

# 被 Class.forName 引用的 Json：名称固定，成员按常规定义保留
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

# 资源文件原样保留（pacc-client.properties 由 ClientConfig 按路径读取）
-keepdirectories

# 库依赖引用缺失只告警不中断（protobuf 作为 library jar 提供，JDK 模块类由 ProGuard 自动识别）
-ignorewarnings
-verbose
