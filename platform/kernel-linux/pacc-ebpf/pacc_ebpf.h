/* PACC 内核层 eBPF —— 内核程序与用户态 loader 之间的事件契约（字节级稳定）
 *
 * 为什么单独抽一个头文件：eBPF 程序（clang -target bpf）与用户态 loader
 * （宿主机 clang/gcc + libbpf）是两个编译单元、两种 ABI。事件结构一旦对不齐，
 * 用户态解出来的是**不报错的垃圾数据**——没有异常、没有崩溃，只有结论错。
 * 因此把它做成单一事实来源，两边都 include 这一份。
 *
 * 修改本文件前先读这三条约束：
 *   1. 固定长度、无指针。它要跨 ring/perf buffer 边界，指针在另一端无意义；
 *   2. 自然对齐、无隐式 padding 空洞。否则不同编译目标的 padding 规则可能不一致；
 *   3. 只能往尾部追加字段，并同步抬 PACC_EVENT_ABI_VERSION。旧 loader 解新事件
 *      时靠 payload_len/ABI 版本号判断，不能靠猜。
 */
#ifndef PACC_EBPF_H
#define PACC_EBPF_H

/* BPF 侧：pacc_common.bpf.h 会先包含 vmlinux.h，__u8/__u32/__u64 已由它提供。
 * 用户态侧：补齐同名的内核风格定宽类型。
 *
 * 为什么这里不写 uint32_t/uint64_t 而是照抄内核的写法：loader 同时 include 了
 * 本头文件与 <linux/bpf.h>（后者经 <linux/types.h> 定义 __u32/__u64）。若这里写
 * `typedef uint64_t __u64`，在 LP64 上 uint64_t 是 unsigned long，而内核的 __u64
 * 是 unsigned long long——**不同**类型的 typedef 重定义在 C 里是错误，loader 会
 * 直接编不过。照抄内核写法后两边类型完全一致，C11 允许这种重定义。 */
#ifdef PACC_BPF_TARGET
/* vmlinux.h 已提供 __u8..__u64 */
#else
typedef unsigned char __u8;
typedef unsigned short __u16;
typedef unsigned int __u32;
typedef unsigned long long __u64;
typedef signed char __s8;
typedef signed short __s16;
typedef signed int __s32;
typedef signed long long __s64;
#endif

/* 事件契约版本。改了 struct pacc_event 的字段语义/顺序就要 +1。 */
#define PACC_EVENT_ABI_VERSION 1

/* comm 取内核 TASK_COMM_LEN（16），不多不少：bpf_get_current_comm 就写这么多。 */
#define PACC_COMM_LEN        16
/* 单条事件的定长载荷：路径 / 模块参数 / 库路径 / 被注入目标名。
 * 256 是权衡：128 装不下容器里动辄 150+ 字符的路径，512 会让环缓冲单事件
 * 翻倍而大部分事件用不到，且栈上再放一个大缓冲有撞 512B 栈上限的风险。 */
#define PACC_PAYLOAD_LEN     256

/* 事件类型（wire 值，只能追加不能重排）
 *
 * 为什么同一挂载点分「普通 / 可疑」两个类型：eBPF 侧命中特征时立刻打高severity，
 * 用户态不必为了判断「这条 execve 要不要上报」而把每条事件再过一遍规则表。
 * 用户态仍可升级/降级（它看得到完整路径，规则更全），但默认分级在采集端就定了。 */
enum pacc_event_type {
	PACC_EV_UNKNOWN = 0,
	PACC_EV_EXECVE = 1,		/* 进程创建（execve 成功进入内核态即记） */
	PACC_EV_EXECVE_SUSPICIOUS = 2,	/* 进程名命中调试/逆向工具表 */
	PACC_EV_OPENAT = 3,		/* 文件打开（默认只在 capture_all_openat 时产出） */
	PACC_EV_OPENAT_SENSITIVE = 4,	/* 打开敏感路径（/dev/mem、/proc/<pid>/mem 等） */
	PACC_EV_PTRACE = 5,		/* ptrace 调用（记录 request 明细） */
	PACC_EV_PTRACE_DANGEROUS = 6,	/* ATTACH / POKETEXT / POKEDATA */
	PACC_EV_PROCESS_VM_WRITEV = 7,	/* 跨进程内存写 */
	PACC_EV_LOAD_MODULE = 8,	/* 内核模块加载 */
	PACC_EV_UPROBE_HOOK = 9,	/* 用户态函数被命中（dlopen 等注入入口） */
	PACC_EV_MAX = 10,
};

/* 事件来源挂载点：放进 flags 高位，便于按挂载点分别统计「命中/丢包」，
 * 也便于用户态在事件类型语义重叠时区分来源（如 syscall tracepoint vs kprobe）。 */
enum pacc_probe_id {
	PACC_PROBE_EXECVE = 1,
	PACC_PROBE_OPENAT = 2,
	PACC_PROBE_PTRACE = 3,
	PACC_PROBE_PROCESS_VM_WRITEV = 4,
	PACC_PROBE_LOAD_MODULE = 5,
	PACC_PROBE_UPROBE = 6,
};

#define PACC_PROBE_SHIFT 24
#define PACC_PROBE_SET(probe, flags) \
	(((__u32)(flags) & 0x00ffffffu) | (((__u32)(probe) & 0xffu) << PACC_PROBE_SHIFT))
#define PACC_PROBE_OF(flags) ((__u32)(flags) >> PACC_PROBE_SHIFT)
#define PACC_FLAG_OF(flags)  ((__u32)(flags) & 0x00ffffffu)

/* 事件载荷形态：payload 里放的是什么，用户态据此决定怎么解析/打印。 */
enum pacc_payload_kind {
	PACC_PAYLOAD_NONE = 0,		/* payload 为空 */
	PACC_PAYLOAD_PATH = 1,		/* NUL 结尾的路径 */
	PACC_PAYLOAD_ARGS = 2,		/* NUL 结尾的命令行/模块参数 */
	PACC_PAYLOAD_NAME = 3,		/* NUL 结尾的名称（库名/符号名） */
	PACC_PAYLOAD_IOV64 = 4,		/* 裸二进制：struct pacc_iovec64（见下） */
};

/* 每个事件类型的 payload 形态（改 payload 语义必须同时改这张表）：
 *   EXECVE / EXECVE_SUSPICIOUS : PATH   —— execve 的文件名（可能含路径）
 *   OPENAT / OPENAT_SENSITIVE  : PATH
 *   PTRACE / PTRACE_DANGEROUS  : NONE
 *   PROCESS_VM_WRITEV          : IOV64  —— 16 字节 {目标地址, 长度}，本机字节序
 *   LOAD_MODULE                : ARGS   —— load_module 的 uargs（通常为空串）
 *   UPROBE_HOOK                : NAME   —— dlopen 的库路径 / dlsym 的符号名
 */

/* process_vm_writev 的目标 iovec（只取第一个）。
 * 为什么自己定义而不 include <linux/uio.h>：UAPI 头与 vmlinux.h 在 BPF 目标下
 * 重复定义 struct iovec。只支持 64 位平台，此时 iovec 就是两个 8 字节字段。 */
struct pacc_iovec64 {
	__u64 iov_base;
	__u64 iov_len;
};

#define PACC_IOV_PAYLOAD_LEN 16	/* sizeof(struct pacc_iovec64)，供用户态校验 */

/* 固定长度、自然对齐、无空洞（336 字节，见下面的静态断言）。
 * 字段偏移写死在注释里，是为了让改结构的人一眼看到跨端影响的字段。 */
struct pacc_event {
	__u64 ts_ns;		/* 0  : bpf_ktime_get_ns()，单调时钟，只用于算采集时延 */
	__u64 arg0;		/* 8  : 挂载点相关原始参数（各自 .bpf.c 里说明语义） */
	__u64 arg1;		/* 16 : 同上 */
	__u64 arg2;		/* 24 : 同上 */
	__u32 pid;		/* 32 : 线程 id（== tgid 时为主线程） */
	__u32 tgid;		/* 36 : 进程 id */
	__u32 ppid;		/* 40 : 父进程 id，CO-RE 读 task->real_parent->pid */
	__u32 uid;		/* 44 */
	__u32 gid;		/* 48 */
	__u32 type;		/* 52 : enum pacc_event_type */
	__u32 payload_len;	/* 56 : payload 有效字节数（不含结尾 NUL） */
	__u32 flags;		/* 60 : 高 8 位挂载点，低 24 位挂载点自定义掩码 */
	char  comm[PACC_COMM_LEN];	/* 64 : bpf_get_current_comm() */
	__u8  payload[PACC_PAYLOAD_LEN];	/* 80 : 形态见 payload_kind */
};

/* ---------- BPF map 名（内核与用户态必须用同一组字面量） ---------- */
#define PACC_MAP_PERF_EVENTS   "pacc_perf_events"	/* PERF_EVENT_ARRAY，5.4 起可用 */
#define PACC_MAP_RINGBUF       "pacc_events"		/* RINGBUF，需 5.8+ */
#define PACC_MAP_CONFIG        "pacc_config"		/* ARRAY[1]，见 struct pacc_config */

/* 运行期配置。用 ARRAY map 而不是 .rodata：.rodata 在部分 libbpf 版本上会被
 * 冻结（bpf_map_freeze），「加载后还能改」这件事不能指望；ARRAY 语义明确，
 * 且用户态随时可以改阈值而不用重新加载程序。 */
struct pacc_config {
	__u32 capture_all_openat;	/* 1 = openat 全量上报（默认 0，只报敏感路径） */
	__u32 capture_all_execve;	/* 1 = execve 全量上报（默认 1，进程创建监控本就全量） */
	__u32 reserved0;
	__u32 reserved1;
};

/* 编译期断言：C99/C11 通用的负数组技巧，避免依赖 _Static_assert 在不同
 * 编译目标下的可用性（vmlinux.h 与宿主机头文件都干净）。 */
#define PACC_STATIC_ASSERT(cond, name) \
	typedef char pacc_static_assert_##name[(cond) ? 1 : -1]

PACC_STATIC_ASSERT(sizeof(struct pacc_event) == 336, event_is_336_bytes);
PACC_STATIC_ASSERT(__builtin_offsetof(struct pacc_event, pid) == 32, pid_offset);
PACC_STATIC_ASSERT(__builtin_offsetof(struct pacc_event, comm) == 64, comm_offset);
PACC_STATIC_ASSERT(__builtin_offsetof(struct pacc_event, payload) == 80, payload_offset);
PACC_STATIC_ASSERT(PACC_EV_MAX <= 256, event_type_fits_in_byte);

#endif /* PACC_EBPF_H */