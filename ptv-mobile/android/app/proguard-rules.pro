# PACC 移动端 release 构建的 ProGuard / R8 规则
#
# 这里刻意保持精简：绝大部分 keep 已由两处上游规则覆盖，重复声明只会让人误以为它们在本文件里生效。
#   - AGP 的 proguard-android-optimize.txt：@JavascriptInterface 方法、native 方法、
#     运行时注解属性、Parcelable.CREATOR、枚举 values/valueOf 等；
#   - Capacitor 通过 consumerProguardFiles 下发：@CapacitorPlugin 类及其 @PluginMethod /
#     @PermissionCallback 方法、`class * extends com.getcapacitor.Plugin`、org.apache.cordova.* 插件。
#
# 后者尤其关键：capacitor.plugins.json 与 cordova_plugins.js 里的 classpath 是**字符串**，
# Capacitor 按名 Class.forName 加载。那条 `-keep ... extends com.getcapacitor.Plugin` 保住了类名，
# 插件才不会被混淆成 a.b.c 后加载失败。

# 崩溃栈可读性：R8 默认不保留这两项属性，release 崩溃栈会退化成
# `at a.b.c(SourceFile:0)` 这种无法定位的形态。加固后代码本就难读，再丢行号等于放弃排障。
-keepattributes SourceFile,LineNumberTable
# 保留属性但抹掉真实文件名，避免反编译者从文件名反推模块划分。
-renamesourcefileattribute SourceFile

# 未启用 -repackageclasses / -overloadaggressively：
# 二者能进一步打散包结构，但会把「按全限定名反射加载」的路径一并改掉。
# 本应用存在 manifest 组件与字符串 classpath 两类按名查找，收益不足以抵偿 release 崩溃风险。
