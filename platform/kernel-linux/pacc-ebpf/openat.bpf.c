/* PACC eBPF —— tracepoint/syscalls/sys_enter_openat：文件打开监控
 *
 * 目标不是「记录所有文件打开」（那量级是每秒几万条，环缓冲会被淹掉，且正常玩家
 * 与作弊工具在这些事件上毫无区别），而是抓**能读写他人地址空间的入口**：
 *   /dev/mem、/dev/kmem、/proc/kcore          物理内存直读，绕过一切页表保护
 *   /proc/<pid>/mem、/proc/self/mem            他人/自身地址空间读写（改内存的主路径）
 *   /proc/self/pagemap                          页表查询，为物理内存改写做准备
 *   /sys/kernel/debug、/sys/kernel/tracing      内核调试接口
 *   /memfd:                                     匿名落地文件，配合 execve 就是无文件落地
 *
 * 默认只上报命中项（低噪声）。需要全量审计时由用户态把 pacc_config.capture_all_openat
 * 置 1，不用重新加载程序。
 */
#include "pacc_common.bpf.h"

/* 敏感路径前缀，定长 32 字节（含结尾 NUL），连续 .rodata、无指针 */
#define PACC_SENSITIVE_COUNT 8

static const char pacc_sensitive_prefixes[PACC_SENSITIVE_COUNT][PACC_PATH_PATTERN_LEN] = {
	"/dev/mem",
	"/dev/kmem",
	"/proc/kcore",
	"/proc/self/mem",
	"/proc/self/pagemap",
	"/sys/kernel/debug",
	"/sys/kernel/tracing",
	"/memfd:",
};

#define PACC_MEM_SUFFIX     "/mem"
#define PACC_MEM_SUFFIX_LEN 4

/* path 是否以 "/mem" 结尾。
 *
 * tail 由 len 推出，是运行期标量：下面那行显式夹紧不是形式主义——校验器只有看到
 * tail 的上下界，才允许 path[tail + i]（i 展开后为常量）的访问落在缓冲区内。
 * len 已在调用方由 pacc_load_user_str 保证 <= PACC_PAYLOAD_LEN。 */
static __always_inline int pacc_ends_with_mem(const char *path, int len)
{
	int tail;

	if (len < PACC_MEM_SUFFIX_LEN)
		return 0;
	tail = len - PACC_MEM_SUFFIX_LEN;
	if (tail < 0 || tail > PACC_PAYLOAD_LEN - PACC_MEM_SUFFIX_LEN)
		return 0;
#pragma unroll
	for (int i = 0; i < PACC_MEM_SUFFIX_LEN; i++) {
		if (path[tail + i] != PACC_MEM_SUFFIX[i])
			return 0;
	}
	return 1;
}

/* 命中返回 1..N（序号写进 flags 便于归因），未命中返回 0。 */
static __always_inline int pacc_match_sensitive(const char *path, int len)
{
#pragma unroll
	for (int n = 0; n < PACC_SENSITIVE_COUNT; n++) {
		if (pacc_prefix_eq32(path, &pacc_sensitive_prefixes[n][0]))
			return n + 1;
	}
	/* /proc/<pid>/mem 的 pid 段长度不定，前缀匹配覆盖不到，用
	 * 「/proc/ 前缀 + /mem 后缀」两条一起判定。 */
	if (pacc_prefix_eq32(path, "/proc/") && pacc_ends_with_mem(path, len))
		return PACC_SENSITIVE_COUNT + 1;
	return 0;
}

SEC("tracepoint/syscalls/sys_enter_openat")
int pacc_openat(struct trace_event_raw_sys_enter *ctx)
{
	struct pacc_event *e = pacc_event_new();
	int len, hit;

	if (!e)
		return 0;

	/* sys_enter_openat 参数：args[0]=int dfd, args[1]=const char __user *filename,
	 * args[2]=int flags, args[3]=umode_t mode。args[1] 是用户态指针。 */
	len = pacc_load_user_str((char *)e->payload, sizeof(e->payload),
				 (const void *)ctx->args[1]);
	pacc_fill_common(e, PACC_PROBE_OPENAT, PACC_EV_OPENAT);
	e->payload_len = len > 0 ? (__u32)len : 0;
	/* dfd 需要符号扩展：AT_FDCWD 是 -100，按无符号打印会变成天文数字。 */
	e->arg0 = (__u64)(long)(int)ctx->args[0];
	e->arg1 = (__u64)(unsigned int)ctx->args[2];	/* O_* 标志位 */

	if (len <= 0)
		return 0;	/* 路径没读出来，报出去也无法归因，直接丢 */

	hit = pacc_match_sensitive((const char *)e->payload, len);
	if (hit > 0) {
		e->type = PACC_EV_OPENAT_SENSITIVE;
		e->flags = PACC_PROBE_SET(PACC_PROBE_OPENAT, (__u32)hit);
	}

	if (hit > 0 || pacc_cfg_openat_all())
		pacc_emit_event(ctx, e);
	return 0;
}

/* 说明：openat2(2) 是另一个 tracepoint（sys_enter_openat2），没有一并实现。
 * 它目前只有较新的用户态（glibc 2.35+ 的某些路径、容器运行时）会用，作弊工具
 * 基本走经典 openat；等真在现网抓到 openat2 的绕过再说，不预先铺开挂载点。 */