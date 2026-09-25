/* ⚠️ 语法检查用桩文件，不是真头文件 ⚠️
 *
 * 用途：在没有 Linux 内核 BTF / linux-headers 的机器（例如本仓库的开发机为
 * Windows、clang 21 可用但没有 vmlinux.h）上，让 clang -target bpf -fsyntax-only
 * 能真正跑起来，从而检查「括号、类型、函数签名、宏展开、SEC 定义」这类错误。
 *
 * 真实构建必须用 `make bpf` 从 /sys/kernel/btf/vmlinux 生成的 vmlinux.h
 * （见 Makefile）。**绝不能把本目录加进真实构建的 -I 路径**——真实 vmlinux.h
 * 里的类型定义完整且随目标内核变化，桩文件里的只是最小子集，用它编出来的
 * 目标文件在 load 阶段必然因 CO-RE 重定位失败而报废。
 *
 * tests/syntax_check.sh 是唯一会引用本目录的地方。
 */
#ifndef PACC_STUB_VMLINUX_H
#define PACC_STUB_VMLINUX_H

typedef unsigned char __u8;
typedef unsigned short __u16;
typedef unsigned int __u32;
typedef unsigned long long __u64;
typedef signed char __s8;
typedef signed short __s16;
typedef signed int __s32;
typedef signed long long __s64;
typedef __u16 __be16;
typedef __u32 __be32;

/* BTF 派生出来的 bpf 枚举（真实 vmlinux.h 里由 enum bpf_map_type 提供） */
enum bpf_map_type {
	BPF_MAP_TYPE_UNSPEC = 0,
	BPF_MAP_TYPE_HASH = 1,
	BPF_MAP_TYPE_ARRAY = 2,
	BPF_MAP_TYPE_PERF_EVENT_ARRAY = 4,
	BPF_MAP_TYPE_PERCPU_ARRAY = 6,
	BPF_MAP_TYPE_RINGBUF = 27,
};

/* BPF 程序的上下文结构 */
struct bpf_raw_tracepoint_args {
	__u64 args[0];
};

struct trace_entry {
	short unsigned int type;
	unsigned char flags;
	unsigned char preempt_count;
	int pid;
};

/* tracepoint/syscalls/sys_enter_* 的上下文：
 * bpf_helpers 侧的 args[6] 是系统调用的原始参数（全为用户态指针或标量）。 */
struct trace_event_raw_sys_enter {
	struct trace_entry ent;
	long int id;
	unsigned long int args[6];
	char __data[];
};

/* vmlinux.h 里的寄存器快照（x86_64 字段名，供 bpf_tracing.h 的
 * PT_REGS_PARM*_CORE 使用）。 */
struct pt_regs {
	unsigned long int r15;
	unsigned long int r14;
	unsigned long int r13;
	unsigned long int r12;
	unsigned long int bp;
	unsigned long int bx;
	unsigned long int r11;
	unsigned long int r10;
	unsigned long int r9;
	unsigned long int r8;
	unsigned long int ax;
	unsigned long int cx;
	unsigned long int dx;
	unsigned long int si;
	unsigned long int di;
	unsigned long int orig_ax;
	unsigned long int ip;
	unsigned long int cs;
	unsigned long int flags;
	unsigned long int sp;
	unsigned long int ss;
};

struct list_head {
	struct list_head *next;
	struct list_head *prev;
};

/* 只保留 pacc_common.bpf.h 的 pacc_ppid() 用到的那几个字段 */
struct task_struct {
	struct task_struct *real_parent;
	struct task_struct *parent;
	int pid;
	int tgid;
};

#endif /* PACC_STUB_VMLINUX_H */