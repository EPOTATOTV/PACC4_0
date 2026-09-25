/* PACC 内核层 eBPF —— 六个挂载点共用的基础设施
 *
 * 内容：vmlinux.h/libbpf 头的包含顺序、事件输出 map（ringbuf 与 perf 二选一）、
 *       配置 map、以及若干「校验器友好」的定长字符串比较/抽取助手。
 *
 * 为什么每个 .bpf.c 是独立对象而不是合成一个大程序：六个挂载点是六个独立内核
 * 程序，用户态要能做到「某个挂载点内核不支持时只丢这一个，其余照常工作」
 * （见 loader 的逐个 attach 失败处理）。合成一个就失去了这个粒度。
 */
#ifndef PACC_COMMON_BPF_H
#define PACC_COMMON_BPF_H

/* 必须先于一切 libbpf 头，且只在 BPF 目标下有意义 */
#define PACC_BPF_TARGET 1
#include "vmlinux.h"

#include <bpf/bpf_helpers.h>
#include <bpf/bpf_tracing.h>
#include <bpf/bpf_core_read.h>

#include "pacc_ebpf.h"

/* GPL：bpf_probe_read_user_str / bpf_probe_read_kernel 是 GPL-only helper，
 * 换成别的许可证会在加载时报 "cannot call GPL-restricted function"。
 * 放在公共头里，保证六个对象文件各带一份。 */
char LICENSE[] SEC("license") = "GPL";

/* ============================ 事件输出 map ============================
 *
 * 缓冲类型是**编译期**二选一，不是运行期 switch：
 *   * BPF_MAP_TYPE_RINGBUF 需要 5.8+。在 5.4 上执行 map create 返回 -EINVAL，
 *     而 libbpf 在 load 程序之前要建好对象里的**所有** map，一个 map 建不出来
 *     整个对象就加载失败。所以两个 map 不能同时出现在一个对象里。
 *   * 因此 Makefile 用同一个 .bpf.c 编两份：-DPACC_USE_RINGBUF=1（ringbuf）与
 *     不定义（perf event array，5.4 起可用）。loader 按内核版本挑对象文件，并在
 *     ringbuf 对象加载失败时自动退回 perf 对象（见 README 的兼容性矩阵）。
 *
 * 为什么优先 ringbuf：无 per-CPU 副本、无「丢失计数漂移」、事件天然有序。
 * 保留 perf 路径的唯一原因是 5.4~5.7 上它才是唯一可用的——设计文档两个都点名了，
 * 这里不偷偷只实现一个。
 */
#ifdef PACC_USE_RINGBUF
struct {
	__uint(type, BPF_MAP_TYPE_RINGBUF);
	__uint(max_entries, 1 << 24);	/* 16 MiB：吸收 execve 风暴时的突发量 */
} pacc_ringbuf SEC(".maps");
#else
/* max_entries 在 load 前由 loader 按实际 CPU 数设置（bpf_map__set_max_entries），
 * 写死一个值在 CPU 数更多的机器上会漏事件。
 * key_size/value_size 显式写成 4：PERF_EVENT_ARRAY 的语义要求如此，
 * 不依赖 libbpf 的默认值推断，免得换 libbpf 版本后行为漂移。 */
struct {
	__uint(type, BPF_MAP_TYPE_PERF_EVENT_ARRAY);
	__uint(key_size, sizeof(__u32));
	__uint(value_size, sizeof(__u32));
	__uint(max_entries, 256);
} pacc_perf_events SEC(".maps");
#endif

/* BPF_F_CURRENT_CPU 是 UAPI 宏而不是 BTF 里的枚举，因此**不在** vmlinux.h 里。
 * 不同 libbpf 版本是否通过 bpf_helpers.h 旁路带出它并不一致，这里自带兜底定义，
 * 避免「换了 libbpf 就编不过」这种最难查的环境问题。 */
#ifndef BPF_F_CURRENT_CPU
#define BPF_F_CURRENT_CPU 0xffffffffULL
#endif

/* 事件暂存区：BPF 栈上限是 512 字节，而 struct pacc_event 就有 336 字节，
 * 再往栈上放一个 256 字节的路径缓冲必然超限（校验器直接报 "BPF program is too
 * large / stack limit exceeded"）。所以事件体在 per-CPU map 里拼装，路径直接读进
 * e->payload，栈上只剩指针和几个标量（约 40 字节）。
 *
 * 用 PERCPU_ARRAY 而不是普通 ARRAY：普通 ARRAY 的同一份值会被其他 CPU 并发改写，
 * 拼到一半就被覆盖。per-CPU 版本天然免锁。
 * 不重入是安全的：一个 tracepoint 处理函数不会在同 CPU 上再触发另一个 tracepoint
 * 处理函数（处理流程不睡眠、不再次进入用户态）。 */
struct {
	__uint(type, BPF_MAP_TYPE_PERCPU_ARRAY);
	__uint(max_entries, 1);
	__type(key, __u32);
	__type(value, struct pacc_event);
} pacc_scratch SEC(".maps");

static __always_inline struct pacc_event *pacc_event_new(void)
{
	__u32 key = 0;

	/* 合法 key 上 PERCPU_ARRAY 必有元素，但仍显式判空：校验器要求所有
	 * 可能为空的返回值在使用前被检查。 */
	return bpf_map_lookup_elem(&pacc_scratch, &key);
}

/* 运行期配置。用 ARRAY map 而不是 .rodata：.rodata 在部分 libbpf 版本上会被
 * 冻结（bpf_map_freeze），「加载后还能改」这件事不能指望；ARRAY 语义明确，
 * 且用户态可以随时改阈值而不用重新加载程序。 */
struct {
	__uint(type, BPF_MAP_TYPE_ARRAY);
	__uint(max_entries, 1);
	__type(key, __u32);
	__type(value, struct pacc_config);
} pacc_config SEC(".maps");

/* 读配置位。读失败一律退回 0（= 保守值：少报而不是错报），绝不让一次配置读取
 * 失败变成整个挂载点哑掉。 */
static __always_inline __u32 pacc_cfg_openat_all(void)
{
	__u32 key = 0;
	struct pacc_config *cfg = bpf_map_lookup_elem(&pacc_config, &key);

	return cfg ? cfg->capture_all_openat : 0;
}

static __always_inline __u32 pacc_cfg_execve_all(void)
{
	__u32 key = 0;
	struct pacc_config *cfg = bpf_map_lookup_elem(&pacc_config, &key);

	return cfg ? cfg->capture_all_execve : 0;
}

/* ============================ 字符串比较助手 ============================
 *
 * 全篇不使用 bpf_strstr —— 这个 helper 在内核主线里**不存在**。设计文档的示例
 * 代码写了它，照抄的后果是编译期隐式声明错误，即使侥幸编译过也会在加载时报
 * "unknown func"。也不使用 bpf_loop()（5.17 才引入，而下限是 5.4）。
 *
 * 替代方案与取舍：
 *   * __builtin_memcmp 做等长比较：要求模式串定长填充，于是只能精确匹配，
 *     抓不到 gdb-12.1 / frida-trace / ghidraRun 这类「工具名+版本/子命令」，
 *     漏报率太高；
 *   * 逐字节前缀匹配：模式串读到 NUL 即算命中，覆盖上述形态。代价是循环必须
 *     完整展开（见下），二进制体积换命中率。这里选前缀匹配。
 * 用户态 loader 会用完整路径再做一次子串匹配（规则更全、可热更），内核侧这层
 * 是「及时性」而非「完备性」的保证。
 *
 * #pragma unroll 为什么是必需而非优化：
 *   value 是栈上定长缓冲，pattern 是 .rodata 常量。只有展开后 i 才是编译期常量，
 *   校验器看到的才是「常量偏移的栈访问」；不展开则 i 是运行期标量，校验器会要求
 *   每轮都有显式边界检查，多数情况下直接拒绝加载。
 *   于是循环上界必须是编译期字面量、不能做成函数参数——这就是为什么下面把两种
 *   宽度各写一份，而不是写一个带 width 参数的通用函数。
 */
#define PACC_SUSPECT_NAME_LEN  16	/* 可疑工具名（含结尾 NUL）定长 */
#define PACC_PATH_PATTERN_LEN  32	/* 敏感路径前缀（含结尾 NUL）定长 */

/* 前缀匹配，用于可疑工具名。模式串耗尽即命中。 */
static __always_inline int pacc_prefix_eq16(const char *value, const char *pattern)
{
#pragma unroll
	for (int i = 0; i < PACC_SUSPECT_NAME_LEN; i++) {
		char pc = pattern[i];

		if (pc == '\0')
			return 1;	/* 模式串读完且逐字节相等 → 命中 */
		if (value[i] != pc)
			return 0;
	}
	return 1;
}

/* 前缀匹配，用于敏感路径（最长 "/sys/kernel/tracing" 19 字节）。 */
static __always_inline int pacc_prefix_eq32(const char *value, const char *pattern)
{
#pragma unroll
	for (int i = 0; i < PACC_PATH_PATTERN_LEN; i++) {
		char pc = pattern[i];

		if (pc == '\0')
			return 1;
		if (value[i] != pc)
			return 0;
	}
	return 1;
}

/* basename 偏移（指向最后一个 '/' 之后）。全展开，末位扫描只读常量栈偏移。
 *
 * 循环后的夹紧不是洁癖：off 是运行期标量，校验器只有看到显式上界检查，才会
 * 允许后续 base[i]（i 为展开后的常量）的栈访问落在缓冲区内。夹紧失败说明尾段
 * 太短装不下一个工具名，直接放弃该样本——宁漏报不误报，用户态还有一次机会。 */
#define PACC_NAME_MATCH_MAX  PACC_SUSPECT_NAME_LEN

static __always_inline int pacc_basename_off(const char *path)
{
	int off = 0;

#pragma unroll
	for (int i = 0; i < PACC_PAYLOAD_LEN; i++) {
		char c = path[i];

		if (c == '\0')
			break;
		if (c == '/')
			off = i + 1;
	}
	if (off < 0 || off > PACC_PAYLOAD_LEN - PACC_NAME_MATCH_MAX)
		return -1;
	return off;
}

/* ============================ 载荷读取 ============================
 *
 * sys_enter_* tracepoint 的 args[] 全是**用户态指针**，必须用
 * bpf_probe_read_user_str，不能用 bpf_probe_read_kernel_str——后者按内核地址
 * 解析，会把用户地址当内核地址读，返回 -EFAULT，静默没有数据。
 *
 * 5.4 的缺口与取舍：5.4 已有 CO-RE，但 bpf_probe_read_user_str 是 **5.5** 才引入
 * 的（helper id 114），5.4 上调它报 "unknown func"。对 5.4 有硬需求时用
 * -DPACC_COMPAT_54=1 重新编译（Makefile 的 bpf-compat54 目标），退回
 * bpf_probe_read_str：它按地址区间自动判别内核/用户，语义更松，理论上存在把内核
 * 地址当用户地址读到的可能。默认（>=5.5 / 5.8）走严格版。
 */
#ifdef PACC_COMPAT_54
#define pacc_read_user_str(dst, size, src) bpf_probe_read_str((dst), (size), (src))
#define pacc_read_user(dst, size, src)     bpf_probe_read((dst), (size), (src))
#else
#define pacc_read_user_str(dst, size, src) bpf_probe_read_user_str((dst), (size), (src))
#define pacc_read_user(dst, size, src)     bpf_probe_read_user((dst), (size), (src))
#endif

/* 读用户态字符串到栈缓冲，返回有效长度（不含结尾 NUL），失败返回 0。
 * 三个返回值分支都显式检查：bpf_probe_read_* 失败返回负 errno，负值一旦被当成
 * 长度用于后续索引，校验器会拒绝（而人类会直接看错）。
 * 缓冲必须先清零：读失败时留下的是上一轮残留数据，会凭空伪造出「路径」。 */
static __always_inline int pacc_load_user_str(char *dst, __u32 size, const void *src)
{
	long ret;

	__builtin_memset(dst, 0, size);
	if (!src)
		return 0;
	ret = pacc_read_user_str(dst, size, src);
	if (ret <= 0)
		return 0;
	if ((__u32)ret > size)
		ret = size;
	/* ret 含结尾 NUL，减 1 才是有效字节数；此时 dst 一定 NUL 结尾 */
	return (int)ret - 1;
}

/* ============================ 事件公共字段 ============================ */

/* 父进程 id。走 CO-RE 读 task->real_parent->pid：
 * 不用 bpf_get_current_task_btf()（5.8+），因为下限是 5.4。
 * 每层指针都判空并检查 bpf_core_read 返回值——校验器会验证 null 检查，少了它
 * 就是「无界指针追逐」，程序直接拒。 */
static __always_inline __u32 pacc_ppid(void)
{
	struct task_struct *task = (struct task_struct *)bpf_get_current_task();
	struct task_struct *parent = NULL;
	int pid = 0;

	if (!task)
		return 0;
	if (bpf_core_read(&parent, sizeof(parent), &task->real_parent) != 0 || !parent)
		return 0;
	if (bpf_core_read(&pid, sizeof(pid), &parent->pid) != 0)
		return 0;
	return pid > 0 ? (__u32)pid : 0;
}

static __always_inline void pacc_fill_common(struct pacc_event *e, __u32 probe, __u32 type)
{
	__u64 pid_tgid = bpf_get_current_pid_tgid();
	__u64 uid_gid = bpf_get_current_uid_gid();

	e->ts_ns = bpf_ktime_get_ns();
	e->pid = (__u32)pid_tgid;
	e->tgid = (__u32)(pid_tgid >> 32);
	e->ppid = pacc_ppid();
	e->uid = (__u32)uid_gid;
	e->gid = (__u32)(uid_gid >> 32);
	e->type = type;
	e->flags = PACC_PROBE_SET(probe, 0);
	e->payload_len = 0;
	e->arg0 = 0;
	e->arg1 = 0;
	e->arg2 = 0;
	bpf_get_current_comm(&e->comm, sizeof(e->comm));
}

/* 提交事件。两个变体的调用签名不同（perf 需要 ctx 定位 CPU），差异收在这里，
 * 六个 .bpf.c 里统一写 pacc_emit_event(ctx, &e)。 */
#ifdef PACC_USE_RINGBUF
#define pacc_emit_event(ctx, e) bpf_ringbuf_output(&pacc_ringbuf, (e), sizeof(*(e)), 0)
#else
#define pacc_emit_event(ctx, e) \
	bpf_perf_event_output((ctx), &pacc_perf_events, BPF_F_CURRENT_CPU,	\
			      (e), sizeof(*(e)))
#endif

#endif /* PACC_COMMON_BPF_H */