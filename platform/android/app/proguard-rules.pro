# PACC Android 原生探针模块的 keep 规则
#
# 这个模块只有一个类，但它的名字被 C++ 侧硬编码：native_probe.cpp 导出的符号是
# Java_com_potatotv_pacc_android_NativeProbe_nativeScan（静态注册，非动态注册）。
# 类名、包名、方法名三者任一被混淆，JNI 查找就会失败。
#
# 注意这与 AGP 默认规则里的 `-keepclasseswithmembernames class * { native <methods>; }` 不是一回事：
# 那条按成员名保留，保不住「包名 + 类名」被 R8 重写成 a.b.c。
#
# `{ *; }` 同时锁住 TF_* 位掩码常量——它们与 native_probe.cpp 里的宏一一对应，
# 被内联或改名后 Java 层与 C++ 层的判定会静默错位（不报错，只是检测结果失真）。
-keep class com.potatotv.pacc.android.NativeProbe { *; }
