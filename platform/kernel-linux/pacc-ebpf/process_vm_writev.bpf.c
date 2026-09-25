/* PACC eBPF —— tracepoint/syscalls/sys_enter_process_vm_writev：跨进程内存写
 *
 * 为什么这是最高价值的挂载点：process_vm_writev(2) 让一个进程**不需要 ptrace、
 * 不需要调试权限**就能直接改写另一个进程的地址空间。它绕过了 ptrace 那条最常见
 * 的检测线索，而作弊工具用它改游戏内存（进程内存补丁、假造数据包缓冲区）。
 *
 * 与 /dev/mem 的关系：/dev/mem 需要 root；process_vm_writev 只需要
 * PTRACE_MODE_ATTACH_REALCREDS（同 uid 即可）。所以它对「同机同用户的作弊工具」
 * 才是那个真正拦得住的信号，必须全量上报、不做采样。
 *
 * 参数（sys_enter_process_vm_writev）：
 *   args[0]=pid_t pid          目标进程
 *   args[1]=const struct iovec *lvec   本地缓冲区数组
 *   args[2]=unsigned long liovcnt      本地数组长度（iov 个数）
 *   args[3]=const struct iovec *rvec   目标进程地址数组
 *   args[4]=unsigned long riovcnt      目标数组长度
 *   args[5]=unsigned long flags
 */
#include "pacc_common.bpf.h"

/* struct pacc_iovec64 定义在契约头 pacc_ebpf.h 里（内核侧与用户态侧同源）。
 * 这里只读**第一个**目标的 iov_base/iov_len：完整遍历 iovec 数组要在 BPF 里做
 * 用户态数组的变长遍历，收益（多几个地址）低于校验器风险与代码体积。 */

SEC("tracepoint/syscalls/sys_enter_process_vm_writev")
int pacc_process_vm_writev(struct trace_event_raw_sys_enter *ctx)
{
	struct pacc_event *e = pacc_event_new();
	struct pacc_iovec64 first = { 0, 0 };
	long ret;

	if (!e)
		return 0;

	pacc_fill_common(e, PACC_PROBE_PROCESS_VM_WRITEV, PACC_EV_PROCESS_VM_WRITEV);
	e->arg0 = (__u64)(long)(int)ctx->args[0];	/* 目标 pid */
	e->arg1 = ctx->args[2];				/* liovcnt */
	e->arg2 = ctx->args[4];				/* riovcnt */

	/* rvec 是用户态指针；显式检查返回值，读失败就只报「发生了一次跨进程写」，
	 * 不拿未初始化数据当地址报出去。 */
	ret = pacc_read_user(&first, sizeof(first), (const void *)ctx->args[3]);
	if (ret == 0) {
		/* payload 形态 PACC_PAYLOAD_IOV64：16 字节裸 {目标地址, 长度}，
		 * 用户态按 struct pacc_iovec64 解（见 pacc_ebpf.h 的形态表）。 */
		__builtin_memcpy(e->payload, &first, sizeof(first));
		e->payload_len = sizeof(first);
	}

	pacc_emit_event(ctx, e);
	return 0;
}