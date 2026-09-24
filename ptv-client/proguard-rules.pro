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
-repackageclasses 'com.potatotv.paccclient'

# 类名字符串同步适配：代码中以字符串出现的类名会被一并改写，
# 静态检索 "com.potatotv.paccclient.xxx" 不再命中；同时隐藏原始源文件名。
-adaptclassstrings
-renamesourcefileattribute SourceFile

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

# 资源文件原样保留（pacc-client.properties 由 ClientConfig 按路径读取）
-keepdirectories

# 库依赖引用缺失只告警不中断（protobuf 作为 library jar 提供，JDK 模块类由 ProGuard 自动识别）
-ignorewarnings
-verbose
