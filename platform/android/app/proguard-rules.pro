# PACC Android 原生探针模块（AAR）keep 规则
#
# 本文件同时承担两个角色：
#   1) library 模块自身 release 构建的 proguardFiles（见 build.gradle.kts buildTypes.release）
#   2) 通过 consumerProguardFiles 打包进 AAR，合并进「宿主 App」的混淆配置
#
# 因为要作为 consumer 规则下发，这里**只允许 -keep* / -keepattributes 这类保守规则**。
# 严禁出现 -repackageclasses / -optimizations / -allowaccessmodification / -overloadaggressively
# 之类「变换型」指令：consumer 规则作用于宿主 App 的全部类与 dex，写进去等于把 App 的类
# 重打包、动到 App 自身字节码，属于严重越界（模块级加固请放在宿主自己的规则里做）。
# ---------------------------------------------------------------------------

# 原生探针唯一对外类。名字被 C++ 侧硬编码：native_probe.cpp 导出的符号是
# Java_com_potatotv_pacc_android_NativeProbe_nativeScan / ..._nativeDetect（静态注册，非动态注册）。
# 「包名 + 类名 + 方法名 + 描述符」四者任一被混淆，JNI 查找即失败 → UnsatisfiedLinkError。
#
# 注意这与 AGP 默认规则里的 `-keepclasseswithmembernames class * { native <methods>; }`
# 不是一回事：那条按成员名保留，保不住「包名 + 类名」被 R8 重写成 a.b.c。
#
# `{ *; }` 同时锁住 TF_* 位掩码常量——它们与 native_probe.cpp 顶部的宏逐一对应
# （TF_NONE/TF_MODULE/TF_ROOT/TF_DEBUGGER/TF_ROM_TAMP/TF_LIB_HIJACK/TF_SYS_TAMP），
# 被改名后 Java 层与 C++ 层的判定会静默错位（不报错，只是检测结果失真）。
-keep class com.potatotv.pacc.android.NativeProbe { *; }

# 把两个 JNI 方法的名字与描述符再显式钉死。上面的 `{ *; }` 已覆盖，这里作为「负载符号」
# 自说明清单，并防止后续有人在 NativeProbe 上做成员级精细规则时误伤 native 方法。
-keepclassmembers class com.potatotv.pacc.android.NativeProbe {
    static native int nativeScan();
    static native java.lang.String nativeDetect();
}

# 通用兜底：凡含 native 方法的类，类名与方法名一律不改（后续新增 native 方法自动受保护）。
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# 保留注解与泛型签名属性：宿主 App 侧若对本模块做反射 / 序列化（如事件结构上报），
# 缺这些属性会在运行时抛异常。代价是宿主 App 全局保留这些属性（APK 略增），风险为零。
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod