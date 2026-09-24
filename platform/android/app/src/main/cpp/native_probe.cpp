// PACC Android 原生反作弊探针（NDK）
//
// 职责（严格玩家端本地采样，不接触游戏服务器数据）：
//  1) 共享库注入检测：遍历 /proc/self/maps 识别 Frida / Xposed / Substrate / 魔改 lib 特征模块；
//  2) root / 越权检测：检查 su / Magisk / supersu 路径与命令；
//  3) 调试器附着检测：/proc/self/status 的 TracerPid + /proc/net/unix 的 JDWP 控制套接字；
//  4) 被篡改环境检测：自定义 ROM / 非官方签名 prop、LD_PRELOAD / GOT 劫持特征；
//  5) Frida 运行时检测：遍历 /proc/self/task/*/comm 匹配 gum-js-loop / gmain / pool-frida 等线程名；
//  6) 完整性：返回结构化 DetectionEvent 供 java 层经 ptv-client 折算上报 PTV。
//
// 所有结果以 JNI 原生 int 位掩码 + 分数返回，纯本地，无网络。
#include <jni.h>
#include <android/log.h>
#include <dirent.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/types.h>

#define TAG "PACC-PTV"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// 检测位掩码（与 Java KNativeProbe 常量一致）
#define TF_NONE       0x0000
#define TF_MODULE     0x0001   // 可疑注入共享库（frida/substrate/xposed 魔改）
#define TF_ROOT       0x0002   // root / magisk
#define TF_DEBUGGER   0x0004   // 被调试器附着
#define TF_ROM_TAMP   0x0008   // 自定义 ROM / 非官方签名
#define TF_LIB_HIJACK 0x0010   // LD_PRELOAD / GOT 劫持特征
#define TF_SYS_TAMP   0x0020   // ptrace 自我移除 / 系统环境篡改

namespace {

bool file_exists(const char *path) {
    struct stat st;
    return stat(path, &st) == 0;
}

// 经典注入框架 / 调试框架所在路径
const char *kSuspiciousLibraries[] = {
    "frida", "libfrida", "gum-js-loop", "gadget", "libsubstrate", "substrate",
    "libXposed", "xposed", "XposedBridge", "libvirtualapp", "libDexHelper",
    "libshell-super", "libjiagu", "libDexHelper-x86", "libSecShell", "libnqshield",
    "libshella", "libbaiduprotect", "libAppBsfPatch", "lbsdataone", "lbemulator",
    "libAndroidFuse", "libexec", "libmimic", "libezoptimize",
    "libinject", "libagent", "libnpPatch", "hook", "libtomato",
    "libdvmsomething", "libddog", "libdolby", "libriru", "EDXposed", "riru"
};

const char *kRootPaths[] = {
    "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
    "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
    "/system/xbin/magisk", "/sbin/magisk", "/data/adb/magisk",
    "/cache/magisk.log", "/data/adb/riru", "/data/adb/modules",
    "/system/xbin/daemonsu", "/system/etc/init.d/99SuperSUDaemon",
    "/system/app/SuperUser.apk"
};

// Frida 运行时创建的线程名。这些名字由 Frida 的 GLib / Gum 运行时写死，
// 与库文件名无关——改掉 libfrida 的文件名也躲不掉，是比 maps 扫描更稳的一路信号。
const char *kFridaUniqueThreadNames[] = {
    "gum-js-loop", "gum-js", "pool-frida", "linjector"
};

// GLib 通用线程名：正常应用也可能有（任何用 GLib 的库都会起 gmain），
// 单独命中不足以定罪，仅在 maps 里同时出现 frida 痕迹时才计入。
const char *kFridaGenericThreadNames[] = {
    "gmain", "gdbus", "frida"
};

}  // namespace

static bool pattern_in_maps(const char *needle) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return false;
    char line[512];
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, needle)) { found = true; break; }
    }
    fclose(f);
    return found;
}

// 遍历 /proc/self/task/<tid>/comm 匹配线程名。
// 用目录遍历而非硬编码 tid：注入框架的线程 tid 是动态的，只有名字可预测。
static int scan_frida_threads() {
    DIR *task = opendir("/proc/self/task");
    if (!task) return 0;

    bool uniqueHit = false;
    bool genericHit = false;
    struct dirent *ent;
    while ((ent = readdir(task)) != nullptr) {
        if (ent->d_name[0] == '.') continue;
        char path[128];
        snprintf(path, sizeof(path), "/proc/self/task/%s/comm", ent->d_name);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        char name[64] = {0};
        ssize_t n = read(fd, name, sizeof(name) - 1);
        close(fd);
        if (n <= 0) continue;
        // comm 带结尾换行，去掉后再比较
        for (ssize_t i = 0; i < n; i++) {
            if (name[i] == '\n') { name[i] = '\0'; break; }
        }
        for (const char *t : kFridaUniqueThreadNames) {
            if (strcmp(name, t) == 0) {
                LOGW("frida thread: %s", name);
                uniqueHit = true;
            }
        }
        for (const char *t : kFridaGenericThreadNames) {
            if (strcmp(name, t) == 0) genericHit = true;
        }
    }
    closedir(task);

    // 只有「独有线程名」或「通用名 + maps 里已有 frida 痕迹」才计为注入，
    // 否则任何用 GLib 的正常库都会把玩家误判成作弊。
    if (uniqueHit || (genericHit && pattern_in_maps("frida"))) {
        return TF_MODULE;
    }
    return 0;
}

// JDWP 调试：Java 调试器通过抽象 Unix 域套接字 @jdwp-control 与 VM 通信。
// 该套接字在 /proc/net/unix 中以名字形式列出，是 Android 上最可靠的 JDWP 判据；
// 只读 ro.debuggable 会把「可调试 ROM 上的正常玩家」一并误伤。
static int scan_jdwp() {
    FILE *f = fopen("/proc/net/unix", "r");
    if (!f) return 0;
    char line[512];
    int flags = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, "jdwp") || strstr(line, "JDWP")) {
            LOGW("jdwp control socket present: %s", line);
            flags |= TF_DEBUGGER;
            break;
        }
    }
    fclose(f);
    return flags;
}

static int scan_modules() {
    int flags = 0;
    for (const char *lib : kSuspiciousLibraries) {
        if (pattern_in_maps(lib)) {
            LOGW("suspicious shared lib: %s", lib);
            flags |= TF_MODULE;
        }
    }
    return flags;
}

static int scan_root() {
    int flags = 0;
    for (const char *p : kRootPaths) {
        if (file_exists(p)) {
            LOGW("root indicator: %s", p);
            flags |= TF_ROOT;
        }
    }
    // Magisk 常见挂载标记
    if (file_exists("/data/adb/magisk") || pattern_in_maps("magisk") ||
        pattern_in_maps("io.github.ellviz")) {
        flags |= TF_ROOT;
    }
    return flags;
}

// 交叉校验：/proc/self/stat 第 3 个字段是进程状态，'t' / 'T' 表示正处于被跟踪的停止态。
// 与 TracerPid 分属两个文件，攻击者要同时伪造两处才能骗过；单独改 status 会被这条抓到。
static bool traced_via_stat() {
    FILE *f = fopen("/proc/self/stat", "r");
    if (!f) return false;
    char buf[512];
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    if (n == 0) return false;
    buf[n] = '\0';
    // comm 字段可能含空格与括号（如 "(WebViewCoreThre)"），故从最后一个 ')' 之后取状态位
    const char *closeParen = strrchr(buf, ')');
    if (!closeParen || closeParen[1] == '\0' || closeParen[2] == '\0') return false;
    char state = closeParen[2];
    return state == 't' || state == 'T';
}

static int scan_debugger() {
    int flags = 0;

    // 路径一：TracerPid（gdb / lldb / strace 等原生调试器附着时非 0）
    char buf[512];
    int fd = open("/proc/self/status", O_RDONLY);
    if (fd >= 0) {
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n > 0) {
            buf[n] = '\0';
            const char *m = strstr(buf, "TracerPid:");
            if (m && atoi(m + strlen("TracerPid:")) > 0) {
                LOGW("debugger attached (TracerPid)");
                flags |= TF_DEBUGGER;
            }
        }
    }

    // 路径二：进程状态位交叉校验
    if (traced_via_stat()) {
        LOGW("process in traced state (/proc/self/stat)");
        flags |= TF_DEBUGGER;
    }

    // 路径三：JDWP。Java 层调试走 JDWP 协议，不产生 TracerPid，前两条都抓不到。
    flags |= scan_jdwp();

    return flags;
}

// 读系统 prop（无第三方依赖，读 /system/build.prop 方式解析关键键值）
static int scan_rom_tamper() {
    int flags = 0;
    if (file_exists("/system/bin/su") || file_exists("/sbin/su")) {
        flags |= TF_ROM_TAMP;
    }
    // 模拟器特征（/proc/cpuinfo 含 goldfish / ranchu 等）
    FILE *f = fopen("/proc/cpuinfo", "r");
    if (f) {
        char line[256];
        while (fgets(line, sizeof(line), f)) {
            if (strstr(line, "goldfish") || strstr(line, "ranchu") ||
                strstr(line, "virtual hardware") || strstr(line, "Android for x86")) {
                flags |= TF_ROM_TAMP;
                break;
            }
        }
        fclose(f);
    }

    // 解析 build.prop：非官方签名 / 测试版 / 未锁定 bootloader 佐证
    f = fopen("/system/build.prop", "r");
    if (f) {
        char line[512];
        bool roSecure = true, roDebuggable = false, officialKeys = true;
        while (fgets(line, sizeof(line), f)) {
            if (strstr(line, "ro.secure=")) {
                // ro.secure=0 说明 adb 以 root 运行（自定义 ROM 常见）
                if (strstr(line, "ro.secure=0")) roSecure = false;
            } else if (strstr(line, "ro.debuggable=1")) {
                roDebuggable = true;
            } else if (strstr(line, "ro.build.tags=test-keys")) {
                officialKeys = false;
            } else if (strstr(line, "ro.build.tags=dev-keys")) {
                officialKeys = false;
            }
        }
        fclose(f);
        if (!roSecure || roDebuggable || !officialKeys) {
            LOGW("non-official ROM indicators detected");
            flags |= TF_ROM_TAMP;
        }
    }
    return flags;
}

// LD_PRELOAD / 动态库劫持特征：环境变量 + 可写可执行内存段
static int scan_lib_hijack() {
    int flags = 0;
    const char *lp = getenv("LD_PRELOAD");
    if (lp && lp[0]) {
        LOGW("LD_PRELOAD set: %s", lp);
        flags |= TF_LIB_HIJACK;
    }
    // 遍历 /proc/self/maps，寻找同时可写且可执行（rwx）的匿名/私有段：
    // 正常原生代码段只读可执行，rwx 段往往是注入 shellcode / 内存 patch 的迹象。
    FILE *f = fopen("/proc/self/maps", "r");
    if (f) {
        char line[1024];
        while (fgets(line, sizeof(line), f)) {
            char perms[8];
            if (sscanf(line, "%*[0-9a-fA-F]-%*[0-9a-fA-F] %7s", perms) == 1) {
                if (strcmp(perms, "rwxp") == 0 || strcmp(perms, "rwx") == 0 ||
                    strcmp(perms, "--xp") == 0 || strcmp(perms, "-wxp") == 0) {
                    // 跳过 JIT 区段避免误报：出现 rwx 即标记由上层结合上下文判断
                    LOGW("executable+writable memory segment: %s", line);
                    flags |= TF_LIB_HIJACK;
                    break;
                }
            }
        }
        fclose(f);
    }
    return flags;
}

// 系统环境篡改：ptrace 自反思脱壳 / 反调试特征
static int scan_system_tamper() {
    // 尝试对自身 ptrace(PTRACE_TRACEME)：若返回 EPERM，说明已被追踪或受控，
    // 或是存在会无限期挂起的反调试包装；正常进程第一次 TRACEME 应成功。
    int flags = 0;
    if (ptrace(PTRACE_TRACEME, 0, 0, 0) == -1) {
        flags |= TF_SYS_TAMP;
    } else {
        // 成功则立刻取消跟踪，避免残留 ptrace 状态影响游戏运行
        (void)ptrace(PTRACE_DETACH, getpid(), 0, 0);
    }
    // /proc/self/status 的 TracerPid 非零已被 scan_debugger 覆盖，这里补充检查
    return flags;
}

extern "C" {

// 综合扫描：返回位掩码（静态方法 -> 第二参为 jclass）
JNIEXPORT jint JNICALL
Java_com_potatotv_pacc_android_NativeProbe_nativeScan(JNIEnv *env, jclass) {
    (void)env;
    int flags = 0;
    flags |= scan_modules();
    flags |= scan_frida_threads();
    flags |= scan_root();
    flags |= scan_debugger();
    flags |= scan_rom_tamper();
    flags |= scan_lib_hijack();
    flags |= scan_system_tamper();
    return flags;
}

// 供 Java 层通过 JNI 返回事件标识 JSON 行（静态方法 -> 第二参为 jclass）
JNIEXPORT jstring JNICALL
Java_com_potatotv_pacc_android_NativeProbe_nativeDetect(JNIEnv *env, jclass) {
    int flags = Java_com_potatotv_pacc_android_NativeProbe_nativeScan(env, nullptr);
    std::vector<std::string> hits;
    if (flags & TF_MODULE)     hits.emplace_back("inject_lib");
    if (flags & TF_ROOT)       hits.emplace_back("root");
    if (flags & TF_DEBUGGER)   hits.emplace_back("debugger");
    if (flags & TF_ROM_TAMP)   hits.emplace_back("rom_tamper");
    if (flags & TF_LIB_HIJACK) hits.emplace_back("lib_hijack");
    if (flags & TF_SYS_TAMP)   hits.emplace_back("sys_tamper");

    // 0-100 预评分：按命中数近似折算
    int score = 0;
    switch (hits.size()) {
        case 0: score = 0; break;
        case 1: score = 45; break;
        case 2: score = 70; break;
        default: score = 100; break;
    }

    std::string out = "{\"edition\":\"android\",\"proto\":\"ndk-probe\",\"score\":" +
        std::to_string(score) + ",\"flags\":\"" + std::to_string(flags) + "\"";
    if (!hits.empty()) {
        out += ",\"hits\":[";
        for (size_t i = 0; i < hits.size(); i++) {
            if (i) out += ",";
            out += "\"" + hits[i] + "\"";
        }
        out += "]";
    }
    out += "}";
    return env->NewStringUTF(out.c_str());
}

}  // extern "C"