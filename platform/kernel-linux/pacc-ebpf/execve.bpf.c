/* PACC eBPF —— tracepoint/syscalls/sys_enter_execve：进程创建监控
 *
 * 两个职责：
 *   1. 进程创建本身（execve 进入内核态即记一条，含路径、父进程、uid）；
 *   2. 进程名命中调试/逆向工具表时立刻打高 severity，不必等用户态过规则。
 *
 * 选择 sys_enter 而不是 sys_exit：sys_exit 时 execve 已经成功换掉了 comm，
 * 拿不到「原来的进程名」，也看不到失败的尝试。作弊工具被拒后重试的过程是有效
 * 情报，sys_enter 都看得到。代价是会把失败的 execve 也记进来（少量噪声）。
 */
#include "pacc_common.bpf.h"

/* 可疑调试/逆向工具名。设计文档 §3.1.2 点名的 11 个，一字不改。
 *
 * 形态说明（这是对文档示例代码的第一处修正）：
 *   `static const char *SUSPICIOUS_NAMES[]` 是**指针数组**——连接器要写重定位，
 *   校验器拿到的是「指向 .rodata 的指针」而非可直接访问的常量，实际无法按文档
 *   那样用。正确的写法是**二维字符数组**：连续 .rodata，无指针、无重定位，
 *   展开后每个下标都是编译期常量，校验器零推理成本。
 *
 * 为什么不做成 const volatile（用户态可改）：.rodata 的「加载后可变」在不同
 * libbpf 版本上受 bpf_map_freeze 影响，语义不保证；而改名单的收益远小于风险。
 * 名单的扩展性交给用户态：loader 会在完整路径上再做一次子串匹配（含
 * scanmem / gameconqueror 等 procfs 回退路径同款特征），内核侧这层负责及时性。
 */
#define PACC_SUSPECT_COUNT 11

static const char pacc_suspect_names[PACC_SUSPECT_COUNT][PACC_SUSPECT_NAME_LEN] = {
	"gdb",
	"lldb",
	"strace",
	"ltrace",
	"frida",
	"cheat-engine",
	"ida",
	"ghidra",
	"radare2",
	"x64dbg",
	"ollydbg",
};

/* 命中则返回 1..PACC_SUSPECT_COUNT（= 命中项序号，写进 flags 便于用户态归因），
 * 未命中返回 0。 */
static __always_inline int pacc_match_suspect(const char *base)
{
#pragma unroll
	for (int n = 0; n < PACC_SUSPECT_COUNT; n++) {
		if (pacc_prefix_eq16(base, &pacc_suspect_names[n][0]))
			return n + 1;
	}
	return 0;
}

SEC("tracepoint/syscalls/sys_enter_execve")
int pacc_execve(struct trace_event_raw_sys_enter *ctx)
{
	struct pacc_event *e = pacc_event_new();
	int len, off, hit;

	if (!e)
		return 0;

	/* args[0] = const char __user *filename。用户态指针，故用 *_user_str。 */
	len = pacc_load_user_str((char *)e->payload, sizeof(e->payload),
				 (const void *)ctx->args[0]);
	pacc_fill_common(e, PACC_PROBE_EXECVE, PACC_EV_EXECVE);
	e->payload_len = len > 0 ? (__u32)len : 0;
	/* args[1] = argv 指针数组地址。不当场解析 argv（要在 BPF 里读指针数组，
	 * 换来的是几百字节的未知收益和一堆校验器风险）；用户态拿到 pid 后
	 * 可以直接读 /proc/<pid>/cmdline，信息更全。 */
	e->arg0 = ctx->args[1];

	if (len > 0) {
		off = pacc_basename_off((const char *)e->payload);
		if (off >= 0) {
			hit = pacc_match_suspect((const char *)e->payload + off);
			if (hit > 0) {
				e->type = PACC_EV_EXECVE_SUSPICIOUS;
				/* flags 低 24 位：命中项序号（1-based） */
				e->flags = PACC_PROBE_SET(PACC_PROBE_EXECVE, (__u32)hit);
			}
		}
	}

	/* 全量上报进程创建，除非用户态显式关掉（极高吞吐场景下按需降噪）。 */
	if (len > 0 || pacc_cfg_execve_all())
		pacc_emit_event(ctx, e);
	return 0;
}