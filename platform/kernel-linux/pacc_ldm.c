// PACC 底层检测 Linux 内核模块（LKM, GPL v2）
// 功能：注册受保护 PID -> kprobe 钩取对受保护进程的越权访问
//       （ksys_ptrace / ksys_process_vm_readv / ksys_write 到 /dev/mem）
//       并通过 debugfs 暴露受保护集合与命中统计，供用户态守护轮询上传 PTV。
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/kprobes.h>
#include <linux/sched.h>
#include <linux/debugfs.h>
#include <linux/fs.h>
#include <linux/uaccess.h>
#include <linux/atomic.h>
#include <linux/spinlock.h>
#include <linux/mutex.h>

MODULE_LICENSE("GPL v2");
MODULE_AUTHOR("Potatotv PACC");
MODULE_DESCRIPTION("PACC-PTV low-level detection (Linux)");
MODULE_VERSION("4.0.0");

#define PACC_MAX_PID   64
#define PACC_DEBUGFS   "pacc"

static DEFINE_SPINLOCK(pacc_lock);
static pid_t      pacc_protected[PACC_MAX_PID];
static atomic64_t pacc_hits_ptrace;
static atomic64_t pacc_hits_pvmread;
static atomic64_t pacc_hits_devmem;
static struct dentry *pacc_dir;

/* ---------- 受保护 PID 集合 ---------- */
static bool pacc_is_protected(pid_t pid)
{
	unsigned long flags;
	bool hit = false;
	if (pid <= 0)
		return false;
	spin_lock_irqsave(&pacc_lock, flags);
	for (int i = 0; i < PACC_MAX_PID; i++) {
		if (pacc_protected[i] == pid) {
			hit = true;
			break;
		}
	}
	spin_unlock_irqrestore(&pacc_lock, flags);
	return hit;
}

static int pacc_add_protected(pid_t pid)
{
	unsigned long flags;
	int slot = -1;

	if (pid <= 0)
		return -EINVAL;
	spin_lock_irqsave(&pacc_lock, flags);
	for (int i = 0; i < PACC_MAX_PID; i++) {
		if (pacc_protected[i] == pid) {
			spin_unlock_irqrestore(&pacc_lock, flags);
			return 0;
		}
		if (pacc_protected[i] == 0 && slot < 0)
			slot = i;
	}
	if (slot >= 0)
		pacc_protected[slot] = pid;
	spin_unlock_irqrestore(&pacc_lock, flags);
	return slot < 0 ? -ENOSPC : 0;
}

/* ---------- kprobe 处理器（x86_64：rdi/rsi/rdx = 第 1/2/3 参） ---------- */

/* ptrace(request=%rdi, pid=%rsi, addr, data)：识别对受保护进程的调试访问 */
static int h_ptrace_pre(struct kprobe *p, struct pt_regs *regs)
{
	if (pacc_is_protected((pid_t)regs->si))
		atomic64_inc(&pacc_hits_ptrace);
	return 0;
}

/* process_vm_readv(pid=%rdi, ...)：跨进程读内取证 */
static int h_pvmread_pre(struct kprobe *p, struct pt_regs *regs)
{
	if (pacc_is_protected((pid_t)regs->di))
		atomic64_inc(&pacc_hits_pvmread);
	return 0;
}

/* write(fd=%rdi, ...)：对 /dev/mem 的写访问（低权限提权/直接写内存） */
static int h_write_pre(struct kprobe *p, struct pt_regs *regs)
{
	if (pid_vnr(current) > 0)
		atomic64_inc(&pacc_hits_devmem);
	return 0;
}

static struct kprobe kp_ptrace  = {
	.symbol_name = "ksys_ptrace",
	.pre_handler = h_ptrace_pre,
};
static struct kprobe kp_pvmread = {
	.symbol_name = "ksys_process_vm_readv",
	.pre_handler = h_pvmread_pre,
};
static struct kprobe kp_write   = {
	.symbol_name = "ksys_write",
	.pre_handler = h_write_pre,
};

static struct kprobe *pacc_probes[] = { &kp_ptrace, &kp_pvmread, &kp_write };

/* ---------- debugfs 接口 ---------- */
static ssize_t add_pid_write(struct file *f, const char __user *buf, size_t n, loff_t *off)
{
	char cmd[16];
	pid_t pid;

	if (n >= sizeof(cmd))
		n = sizeof(cmd) - 1;
	if (copy_from_user(cmd, buf, n))
		return -EFAULT;
	cmd[n] = '\0';
	if (sscanf(cmd, "%d", &pid) != 1)
		return -EINVAL;
	return pacc_add_protected(pid) == 0 ? n : -ENOSPC;
}

static ssize_t clear_pids_write(struct file *f, const char __user *buf, size_t n, loff_t *off)
{
	unsigned long flags;
	spin_lock_irqsave(&pacc_lock, flags);
	memset(pacc_protected, 0, sizeof(pacc_protected));
	spin_unlock_irqrestore(&pacc_lock, flags);
	return n;
}

static ssize_t stats_read(struct file *f, char __user *buf, size_t n, loff_t *off)
{
	char out[128];
	int len = snprintf(out, sizeof(out),
			   "ptrace=%lld pvmread=%lld devmem_write=%lld\n",
			   (long long)atomic64_read(&pacc_hits_ptrace),
			   (long long)atomic64_read(&pacc_hits_pvmread),
			   (long long)atomic64_read(&pacc_hits_devmem));

	if (*off >= len)
		return 0;
	if (n > len - *off)
		n = len - *off;
	if (copy_to_user(buf, out + *off, n))
		return -EFAULT;
	*off += n;
	return n;
}

static const struct file_operations fops_add_pid = {
	.write = add_pid_write,
};
static const struct file_operations fops_clear_pids = {
	.write = clear_pids_write,
};
static const struct file_operations fops_stats = {
	.read = stats_read,
};

static int __init pacc_init(void)
{
	for (int i = 0; i < ARRAY_SIZE(pacc_probes); i++) {
		int ret = register_kprobe(pacc_probes[i]);
		if (ret)
			pr_warn("[PACC] register_kprobe(%s) failed %d\n",
				pacc_probes[i]->symbol_name, ret);
	}

	pacc_dir = debugfs_create_dir(PACC_DEBUGFS, NULL);
	debugfs_create_file("add_protected_pid", 0200, pacc_dir, NULL, &fops_add_pid);
	debugfs_create_file("clear_protected_pids", 0200, pacc_dir, NULL, &fops_clear_pids);
	debugfs_create_file("stats", 0444, pacc_dir, NULL, &fops_stats);

	pr_info("[PACC] loaded\n");
	return 0;
}

static void __exit pacc_exit(void)
{
	for (int i = 0; i < ARRAY_SIZE(pacc_probes); i++)
		unregister_kprobe(pacc_probes[i]);
	debugfs_remove_recursive(pacc_dir);
	pr_info("[PACC] unloaded\n");
}

module_init(pacc_init);
module_exit(pacc_exit);