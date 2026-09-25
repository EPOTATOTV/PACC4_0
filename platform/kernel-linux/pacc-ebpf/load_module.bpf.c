/* PACC eBPF —— kprobe/load_module：内核模块加载监控
 *
 * 反 rootkit 的那条线：作弊工具在 Linux 上最彻底的一招是插一个 LKM 把内存读写
 * 变成「合法」操作、把 /proc 的可见性改掉。所以任何模块加载都必须留痕。
 *
 * 挂载点选择与它的已知弱点（如实记录，不掩饰）：
 *   * 设计文档指定 kprobe/load_module。mainline 里 load_module 是 static 函数，
 *     kprobe 靠 kallsyms 仍能挂上（static 函数也在 kallsyms 里），但不能内联；
 *     内核 6.x 模块加载器重写后符号名/存在性可能变化。
 *   * 因此 loader 会按顺序尝试候选符号：load_module → __x64_sys_finit_module →
 *     __x64_sys_init_module，用 bpf_program__set_attach_target 在运行期改挂载点，
 *     哪个先成功就用哪个，并把这个选择写进日志（见 README「加载与验证」）。
 *
 * 为什么不在这里读模块名：读模块名要么走 struct load_info（文件私有结构，类型
 * 名/字段形态随内核版本漂移），要么走 struct module::name（6.4 前后从字符数组
 * 变成过指针）。这两处一旦 CO-RE 定位失败，**整个对象**加载失败——把「反 rootkit」
 * 这条线做成整套 eBPF 里最脆弱的一环，权衡上不划算。
 * 结论：内核侧只做「有人加载模块了，是谁、什么参数」的可靠记录；模块名由用户态
 * loader 在同一时刻读 /proc/modules 补全（模块表按加载顺序追加，取末尾即为最新）。
 *
 * 参数（load_module(struct load_info *info, const char __user *uargs, int flags)）：
 *   parm1 = struct load_info *（不解析，仅作为指针记进 arg0，便于与其它来源关联）
 *   parm2 = const char __user *uargs（模块参数，通常是空串；用户态指针）
 *   parm3 = int flags（MODULE_INIT_IGNORE_MODVERSIONS / IGNORE_VERMAGIC 等绕过位，
 *                       设了这两位就是在刻意跳过内核的一致性校验，值得记录）
 */
#include "pacc_common.bpf.h"

/* MODULE_INIT_IGNORE_MODVERSIONS=0x1, MODULE_INIT_IGNORE_VERMAGIC=0x2
 * （include/uapi/linux/module.h），两位都设说明加载方主动放弃校验。 */
#define PACC_MODULE_INIT_BYPASS_MASK 0x3

SEC("kprobe/load_module")
int pacc_load_module(struct pt_regs *ctx)
{
	struct pacc_event *e = pacc_event_new();
	const void *info, *uargs;
	int flags, len;

	if (!e)
		return 0;

	/* PT_REGS_PARM*_CORE 而不是 PT_REGS_PARM*：前者对寄存器访问做 CO-RE 包装，
	 * 是 libbpf 在 CO-RE 程序里的要求用法（老写法在跨 arch/内核结构变化时
	 * 会取到错误寄存器）。 */
	info = (const void *)PT_REGS_PARM1_CORE(ctx);
	uargs = (const void *)PT_REGS_PARM2_CORE(ctx);
	flags = (int)PT_REGS_PARM3_CORE(ctx);

	len = pacc_load_user_str((char *)e->payload, sizeof(e->payload), uargs);
	pacc_fill_common(e, PACC_PROBE_LOAD_MODULE, PACC_EV_LOAD_MODULE);
	e->payload_len = len > 0 ? (__u32)len : 0;
	e->arg0 = (__u64)(unsigned long)info;
	e->arg1 = (__u64)(unsigned int)flags;
	/* flags 低 24 位：绕过校验位是否置起（1=有，0=没有） */
	e->flags = PACC_PROBE_SET(PACC_PROBE_LOAD_MODULE,
				  (flags & PACC_MODULE_INIT_BYPASS_MASK) ? 1u : 0u);

	pacc_emit_event(ctx, e);
	return 0;
}