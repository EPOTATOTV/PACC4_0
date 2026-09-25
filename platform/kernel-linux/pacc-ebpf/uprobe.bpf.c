/* PACC eBPF —— uprobe：用户态钩子监控
 *
 * 「用户态钩子」在 Linux 上的落地形态是：作弊库把自己编译进一个 .so，再用
 * dlopen/dlsym 把它拉进游戏进程，或者直接改游戏函数入口。所以监控点选在
 * **动态链接器的装载入口**上，而不是去猜游戏二进制里的函数地址：
 *
 *   dlopen(const char *filename, int flags)   → 新共享库进入本进程
 *   dlsym(void *handle, const char *symbol)   → 解析特定符号（inline hook 第一步）
 *
 * 为什么挂在 dlopen/dlsym 而不是 mmap/mprotect：mmap 量级太大（每次库加载、
 * 每个 arena 分配都会调），dlopen/dlsym 是低频且直接对应「把外来代码装进来」。
 *
 * 挂载方式与 PID 过滤：
 *   uprobe 是 per-PID 挂载的（bpf_program__attach_uprobe_opts 带 pid 参数）。
 *   不能全局挂——否则全世界每个进程的 dlopen 都会打进来，环缓冲瞬间满，
 *   而且非受保护进程的 dlopen 毫无情报价值。loader 只对受保护进程逐个挂，
 *   受保护进程集合变化时重新挂（见 README）。
 *
 * 目标二进制/符号可由 loader 覆盖（--uprobe-lib / --uprobe-sym），默认 libc 的
 * dlopen / dlsym；同一个程序可以拿去挂游戏自己的函数，做「客户端函数是否被
 * 外来代码调用」的探针。
 *
 * 不读 attach cookie（bpf_get_attach_cookie，5.15+）：下限 5.4 用不了。改用
 * 两个独立程序分别挂 dlopen 与 dlsym，把区分度写进 flags 低位。
 */
#include "pacc_common.bpf.h"

#define PACC_UPROBE_DLOPEN 1
#define PACC_UPROBE_DLSYM  2

/* parm1 语义随目标函数而异：dlopen 是库路径，dlsym 是符号名。两者都是
 * 用户态指针（uprobe 命中点仍在用户态），所以必须 pacc_read_user_str。
 * parm2：dlopen 是 flags（RTLD_*），dlsym 是 handle。 */
static __always_inline void pacc_uprobe_emit(struct pt_regs *ctx, __u32 which)
{
	struct pacc_event *e = pacc_event_new();
	int len;

	if (!e)
		return;

	len = pacc_load_user_str((char *)e->payload, sizeof(e->payload),
				 (const void *)PT_REGS_PARM1_CORE(ctx));
	pacc_fill_common(e, PACC_PROBE_UPROBE, PACC_EV_UPROBE_HOOK);
	e->payload_len = len > 0 ? (__u32)len : 0;
	e->arg0 = (__u64)PT_REGS_PARM2_CORE(ctx);	/* flags / handle */
	e->flags = PACC_PROBE_SET(PACC_PROBE_UPROBE, which);
	pacc_emit_event(ctx, e);
}

/* 挂载目标由 loader 运行期指定（bpf_program__attach_uprobe_opts），
 * SEC("uprobe") 只是声明挂载类型。 */
SEC("uprobe")
int pacc_uprobe_dlopen(struct pt_regs *ctx)
{
	pacc_uprobe_emit(ctx, PACC_UPROBE_DLOPEN);
	return 0;
}

SEC("uprobe")
int pacc_uprobe_dlsym(struct pt_regs *ctx)
{
	pacc_uprobe_emit(ctx, PACC_UPROBE_DLSYM);
	return 0;
}