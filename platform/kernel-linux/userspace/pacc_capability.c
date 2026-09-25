/* PACC 内核层能力探测实现
 *
 * 依赖 libbpf 的 bpf() 包装（<bpf/bpf.h>），因此本文件只在能链到 libbpf 时编译。
 * 这一点是刻意的：用裸 syscall(__NR_bpf) 也能做，但 <sys/syscall.h> 的 __NR_bpf
 * 在旧 glibc 上可能缺失（需要自己兜 321），而 bpf() 的入参结构体 union bpf_attr
 * 必须与内核 ABI 逐字段对齐——libbpf 已经保证这件事，不必自己再抄一遍。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/utsname.h>

#include <bpf/bpf.h>

#include "pacc_capability.h"

#define PACC_BTF_VMLINUX   "/sys/kernel/btf/vmlinux"
#define PACC_LDM_DEBUGFS   "/sys/kernel/debug/pacc"

/* 版本门槛（设计文档 §3.1.2 兼容性矩阵）
 *   5.4 起 : CO-RE（BTF 驱动的字段重定位）
 *   5.5 起 : bpf_probe_read_user_str / bpf_probe_read_user（读用户态指针的
 *            严格版 helper；5.4 只有语义更松的 bpf_probe_read_str）
 *   5.8 起 : BPF_MAP_TYPE_RINGBUF
 *   4.15~5.3: 降级 procfs 扫描（pacc_ldm 模块 + 用户态 /proc 遍历）
 */
#define PACC_KV_EBPF_MAJOR		5
#define PACC_KV_EBPF_MINOR		4
#define PACC_KV_USER_READ_MAJOR		5
#define PACC_KV_USER_READ_MINOR		5
#define PACC_KV_RINGBUF_MAJOR		5
#define PACC_KV_RINGBUF_MINOR		8
#define PACC_KV_PROC_MISC_MINOR		15	/* 4.15 */

/* 用 bpf(2) 实测一次最小 map 创建，判定 bpf 子系统是否真的可用。
 * 只信 uname 是不够的：发行版裁剪内核（CONFIG_BPF_SYSCALL=n）、容器 seccomp、
 * 缺 CAP_BPF 都会让「版本够」的机器用不了 eBPF，而这三者的报错原因完全不同，
 * 必须让运维一眼分清，所以把 errno 也带回去。 */
static int pacc_probe_bpf_syscall(int *out_errno, int *out_ringbuf)
{
	union bpf_attr attr;
	int fd;

	memset(&attr, 0, sizeof(attr));
	attr.map_type = BPF_MAP_TYPE_ARRAY;
	attr.key_size = sizeof(unsigned int);
	attr.value_size = sizeof(unsigned int);
	attr.max_entries = 1;

	fd = bpf(BPF_MAP_CREATE, &attr);
	if (fd < 0) {
		*out_errno = errno;
		return 0;
	}
	close(fd);
	*out_errno = 0;

	/* ringbuf 单独实测：5.8 以下 map_type 不认识，返回 EINVAL。
	 * 只有基础 bpf(2) 可用时这个结果才有意义（否则 EPERM 是权限问题而非
	 * 「不支持 ringbuf」），调用方负责这个前提。 */
	memset(&attr, 0, sizeof(attr));
	attr.map_type = BPF_MAP_TYPE_RINGBUF;
	attr.max_entries = 4096;	/* 必须是 2 的幂且为页大小整数倍 */
	fd = bpf(BPF_MAP_CREATE, &attr);
	if (fd < 0) {
		*out_ringbuf = 0;
		return 1;
	}
	close(fd);
	*out_ringbuf = 1;
	return 1;
}

static int pacc_probe_kver(struct pacc_capability *cap)
{
	struct utsname u;
	char buf[128] = "";
	char *p;
	unsigned int ma = 0, mi = 0, pa = 0;

	if (uname(&u) == 0)
		snprintf(buf, sizeof(buf), "%s", u.release);
	if (buf[0] == '\0') {
		/* 容器里 uname 被拦截时改读 procfs（同样可能被拦，读不到就算了） */
		FILE *f = fopen("/proc/sys/kernel/osrelease", "r");

		if (f) {
			if (fgets(buf, sizeof(buf), f))
				buf[sizeof(buf) - 1] = '\0';
			fclose(f);
			p = strchr(buf, '\n');
			if (p)
				*p = '\0';
		}
	}
	if (buf[0] == '\0')
		return 0;

	/* "5.15.0-91-generic" → 5.15.0；sscanf 遇到 '-' 停下，不用手工分词 */
	if (sscanf(buf, "%u.%u.%u", &ma, &mi, &pa) < 2)
		return 0;
	cap->kver_major = ma;
	cap->kver_minor = mi;
	cap->kver_patch = pa;
	cap->have_kver = 1;
	return 1;
}

/* 版本比较：>= major.minor 时返回 1 */
static int pacc_kver_at_least(const struct pacc_capability *cap, unsigned int major,
			      unsigned int minor)
{
	if (!cap->have_kver)
		return 0;
	if (cap->kver_major != major)
		return cap->kver_major > major;
	return cap->kver_minor >= minor;
}

static int pacc_file_readable(const char *path)
{
	return access(path, R_OK) == 0 ? 1 : 0;
}

int pacc_probe_capability(struct pacc_capability *out)
{
	char note[128] = "";
	int bpf_errno = 0, ringbuf = 0;

	if (!out)
		return -1;
	memset(out, 0, sizeof(*out));
	out->have_bpf_syscall = -1;

	out->is_root = (geteuid() == 0) ? 1 : 0;
	out->have_btf = pacc_file_readable(PACC_BTF_VMLINUX);
	out->ldm_module_loaded = pacc_file_readable(PACC_LDM_DEBUGFS);
	pacc_probe_kver(out);
	out->have_probe_read_user =
		pacc_kver_at_least(out, PACC_KV_USER_READ_MAJOR, PACC_KV_USER_READ_MINOR) ? 1 : 0;

	if (pacc_probe_bpf_syscall(&bpf_errno, &ringbuf)) {
		out->have_bpf_syscall = 1;
		out->have_ringbuf = ringbuf;
	} else {
		out->have_bpf_syscall = 0;
		out->bpf_errno = bpf_errno;
	}

	/* ---------- 依次回落，每次都说清理由 ---------- */

	if (!out->have_kver) {
		/* 版本读不到就不能做 eBPF：CO-RE 对象的字段重定位依赖目标内核 BTF，
		 * 连版本都读不到的受限环境（典型是严 seccomp 的容器）里外都不齐。 */
		out->mode = PACC_MODE_PROCFS;
		snprintf(out->reason, sizeof(out->reason),
			 "读不到内核版本（uname 与 /proc/sys/kernel/osrelease 均失败）："
			 "无法确认 CO-RE 兼容性，降级 procfs 扫描");
		return 0;
	}

	if (out->have_bpf_syscall == 0) {
		out->mode = PACC_MODE_PROCFS;
		switch (out->bpf_errno) {
		case EPERM:
		case EACCES:
			snprintf(out->reason, sizeof(out->reason),
				 "bpf() 被拒绝（errno=%d，%s）：需要 root 或 CAP_BPF"
				 "（内核 <5.8 需 CAP_SYS_ADMIN）；降级 procfs 扫描",
				 out->bpf_errno, strerror(out->bpf_errno));
			break;
		case ENOSYS:
			snprintf(out->reason, sizeof(out->reason),
				 "内核没有 bpf() 系统调用（CONFIG_BPF_SYSCALL=n 或内核过旧）；"
				 "降级 procfs 扫描");
			break;
		case EINVAL:
			snprintf(out->reason, sizeof(out->reason),
				 "bpf() 存在但拒绝了最小 map 创建（errno=EINVAL）："
				 "内核未启用 BPF 或启用了 bpf 相关的 LSM 策略；降级 procfs 扫描");
			break;
		default:
			snprintf(out->reason, sizeof(out->reason),
				 "bpf() 调用失败（errno=%d，%s）；降级 procfs 扫描",
				 out->bpf_errno, strerror(out->bpf_errno));
			break;
		}
		return 0;
	}

	if (!pacc_kver_at_least(out, PACC_KV_EBPF_MAJOR, PACC_KV_EBPF_MINOR)) {
		out->mode = PACC_MODE_PROCFS;
		snprintf(out->reason, sizeof(out->reason),
			 "内核 %u.%u.%u 低于 eBPF CO-RE 下限 5.4"
			 "（4.15~5.3 走 procfs 回退：用户态 /proc 扫描 + pacc_ldm 模块）",
			 out->kver_major, out->kver_minor, out->kver_patch);
		return 0;
	}

	if (!out->have_btf) {
		/* CO-RE 对象的字段重定位读的就是这个文件；没有它，-g 编译出来的
		 * .bpf.o 在 load 阶段会因为找不到 BTF 而整批失败。提前判掉，
		 * 免得运维面对一屏 libbpf 报错。 */
		out->mode = PACC_MODE_PROCFS;
		snprintf(out->reason, sizeof(out->reason),
			 "内核 %u.%u.%u 版本够但没有 %s（CONFIG_DEBUG_INFO_BTF=n）："
			 "CO-RE 对象无法加载，降级 procfs 扫描",
			 out->kver_major, out->kver_minor, out->kver_patch, PACC_BTF_VMLINUX);
		return 0;
	}

	if (pacc_kver_at_least(out, PACC_KV_RINGBUF_MAJOR, PACC_KV_RINGBUF_MINOR)) {
		if (out->have_ringbuf) {
			out->mode = PACC_MODE_EBPF_RINGBUF;
			snprintf(out->reason, sizeof(out->reason),
				 "内核 %u.%u.%u：CO-RE + ringbuf 可用，"
				 "选 ringbuf 事件通道（无 per-CPU 副本、不丢序）",
				 out->kver_major, out->kver_minor, out->kver_patch);
		} else {
			out->mode = PACC_MODE_EBPF_PERF;
			snprintf(out->reason, sizeof(out->reason),
				 "内核 %u.%u.%u 版本够但 RINGBUF map 创建失败"
				 "（被 LSM/seccomp 限制？），退回 perf event array 通道",
				 out->kver_major, out->kver_minor, out->kver_patch);
		}
		return 0;
	}

	out->mode = PACC_MODE_EBPF_PERF;
	if (out->have_probe_read_user)
		snprintf(note, sizeof(note), "");
	else
		snprintf(note, sizeof(note),
			 "；注意：该内核缺 bpf_probe_read_user_str（5.5 引入），"
			 "需用 make bpf-compat54 编译的对象（loader 会自动优先尝试）");

	snprintf(out->reason, sizeof(out->reason),
		 "内核 %u.%u.%u：有 CO-RE 但早于 ringbuf（5.8），"
		 "选 perf event array 通道%s",
		 out->kver_major, out->kver_minor, out->kver_patch, note);
	return 0;
}

const char *pacc_mode_name(enum pacc_collect_mode mode)
{
	switch (mode) {
	case PACC_MODE_EBPF_RINGBUF:
		return "ebpf-ringbuf";
	case PACC_MODE_EBPF_PERF:
		return "ebpf-perf";
	case PACC_MODE_PROCFS:
		return "procfs-fallback";
	case PACC_MODE_UNAVAILABLE:
		return "unavailable";
	default:
		return "unknown";
	}
}

int pacc_capability_describe(const struct pacc_capability *cap, char *out, unsigned int outsz)
{
	if (!cap || !out || outsz == 0)
		return -1;
	return snprintf(out, outsz,
			"[PACC] 采集模式=%s（%s）\n"
			"[PACC]   kernel=%u.%u.%u btf=%s bpf()=%s ringbuf=%s root=%s"
			" probe_read_user=%s ldm_module=%s\n",
			pacc_mode_name(cap->mode), cap->reason,
			cap->kver_major, cap->kver_minor, cap->kver_patch,
			cap->have_btf ? "yes" : "no",
			cap->have_bpf_syscall < 0 ? "unknown" :
				(cap->have_bpf_syscall ? "ok" : "denied"),
			cap->have_ringbuf ? "yes" : "no",
			cap->is_root ? "yes" : "no",
			cap->have_probe_read_user ? "yes" : "no",
			cap->ldm_module_loaded ? "loaded" : "absent");
}