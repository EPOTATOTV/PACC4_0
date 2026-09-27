/* PACC 内核层能力探测：决定走 eBPF 还是 procfs 回退路径
 *
 * 为什么需要一个独立模块而不是在 main 里 if-else：这个判断要在两处用
 * （loader 自己、以及排查问题时的人工核对），而且它的输出是**运维可见**的
 * ——「为什么这台机器没用上 eBPF」必须是一句能直接贴进工单的话，而不是一个
 * 布尔值。因此每次判定都带 reason 字符串。
 *
 * 判定策略：**先实测、再看版本**。版本号是必要条件但不是充分条件：
 * 发行版裁剪内核（CONFIG_BPF_SYSCALL=n、CONFIG_DEBUG_INFO_BTF=n）以及
 * 容器里的 seccomp/能力限制都会让「版本够」的内核用不了 eBPF。所以这里真的去
 * 调一次 bpf(2) 建 map，而不是相信 uname。
 */
#ifndef PACC_CAPABILITY_H
#define PACC_CAPABILITY_H

#ifdef __cplusplus
extern "C" {
#endif

enum pacc_collect_mode {
	/* >=5.8 且 bpf(2) 实测可用：ringbuf 事件通道 */
	PACC_MODE_EBPF_RINGBUF = 0,
	/* 5.4~5.7（或 ringbuf 建不出来但 bpf(2) 可用）：perf event array 通道 */
	PACC_MODE_EBPF_PERF = 1,
	/* 4.15~5.3、无 BTF、无 bpf(2) 权限、或 eBPF 对象全部加载失败：procfs 扫描 */
	PACC_MODE_PROCFS = 2,
	/* 连 /proc 都读不到：只能如实说不支持 */
	PACC_MODE_UNAVAILABLE = 3,
};

#define PACC_REASON_MAX 320

struct pacc_capability {
	enum pacc_collect_mode mode;
	/* 选择该模式的一句话理由（直接进日志/工单） */
	char reason[PACC_REASON_MAX];

	unsigned int kver_major, kver_minor, kver_patch;
	int have_kver;		/* 0 = uname 与 /proc 都没读到版本 */

	int have_bpf_syscall;	/* 1 可用 / 0 不可用 / -1 未探测 */
	int bpf_errno;		/* have_bpf_syscall==0 时的 errno，用于区分「没权限」和「内核不支持」 */
	int have_ringbuf;	/* 1 = 实测 BPF_MAP_TYPE_RINGBUF 可创建 */
	int have_btf;		/* 1 = /sys/kernel/btf/vmlinux 存在（CO-RE 必需） */
	int have_probe_read_user;	/* 1 = 内核 >=5.5，有 bpf_probe_read_user_str */
	int is_root;		/* euid == 0 */

	/* procfs 回退路径的现状：pacc_ldm 内核模块是否已加载
	 * （判据是它的 debugfs 目录 /sys/kernel/debug/pacc） */
	int ldm_module_loaded;
};

/* 探测并填充 out；始终返回 0（探测失败也是结果的一部分，不当作错误）。 */
int pacc_probe_capability(struct pacc_capability *out);

const char *pacc_mode_name(enum pacc_collect_mode mode);

/* 把探测结果格式化成多行人类可读文本（日志用）。返回写入长度。 */
int pacc_capability_describe(const struct pacc_capability *cap, char *out, unsigned int outsz);

#ifdef __cplusplus
}
#endif

#endif /* PACC_CAPABILITY_H */