/* PACC eBPF —— tracepoint/syscalls/sys_enter_ptrace：调试/注入检测
 *
 * 用 sys_enter 而不是 kprobe/ksys_ptrace：tracepoint 是稳定 ABI，参数顺序由内核
 * 自己保证；kprobe 打私有函数名一旦被改名/内联就静默失效（而静默失效在反作弊里
 * 等于没有防护）。
 *
 * 危险请求（设计文档 §3.1.2 点名三个）：
 *   PTRACE_POKETEXT (4)   写被跟踪进程的代码段 —— 典型 inline hook / 补丁
 *   PTRACE_POKEDATA (5)   写被跟踪进程的数据段 —— 改游戏内数值
 *   PTRACE_ATTACH   (16)  attach 到运行中的进程 —— 调试器/外挂注入的起点
 * 注意这三个是**常量表**：PTRACE_ATTACH 在 <linux/ptrace.h> 里是 0x10，
 * 用户态 <sys/ptrace.h> 里也是 16，两边一致。
 *
 * 为什么不把 PTRACE_SEIZE (0x4206) 也列为危险：它是「不停止目标」的 attach，
 * 现代调试器与云监控 agent 都用它。它确实可疑，但不可疑到能在内核层直接定性，
 * 留给用户态规则层按进程信誉决定——内核层只做能站得住脚的判定。
 */
#include "pacc_common.bpf.h"

#define PACC_PTRACE_POKETEXT 4
#define PACC_PTRACE_POKEDATA 5
#define PACC_PTRACE_ATTACH   16

static __always_inline int pacc_ptrace_is_dangerous(long request)
{
	return request == PACC_PTRACE_POKETEXT ||
	       request == PACC_PTRACE_POKEDATA ||
	       request == PACC_PTRACE_ATTACH;
}

SEC("tracepoint/syscalls/sys_enter_ptrace")
int pacc_ptrace(struct trace_event_raw_sys_enter *ctx)
{
	struct pacc_event *e = pacc_event_new();
	long request;

	if (!e)
		return 0;

	/* sys_enter_ptrace 参数：args[0]=long request, args[1]=pid_t pid,
	 * args[2]=unsigned long addr, args[3]=unsigned long data。 */
	request = (long)ctx->args[0];
	pacc_fill_common(e, PACC_PROBE_PTRACE, PACC_EV_PTRACE);
	e->arg0 = (__u64)request;
	e->arg1 = (__u64)(long)(int)ctx->args[1];	/* 目标 pid，可为自身 */
	e->arg2 = ctx->args[2];				/* addr / data 视请求而定 */

	if (pacc_ptrace_is_dangerous(request)) {
		e->type = PACC_EV_PTRACE_DANGEROUS;
		e->flags = PACC_PROBE_SET(PACC_PROBE_PTRACE, (__u32)request);
	}

	/* ptrace 是低频高危系统调用，全量上报：漏一条 POKETEXT 的代价远大于
	 * 多收几条 TRACEME 的噪声。 */
	pacc_emit_event(ctx, e);
	return 0;
}