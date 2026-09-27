/* ⚠️ 语法检查用桩文件，不是 libbpf 的真 bpf_helpers.h ⚠️
 * 只提供 pacc-ebpf 下各 .bpf.c 实际用到的宏与 helper 原型；真实构建用系统的
 * libbpf（见 Makefile 的 -I 路径）。理由同 tests/stubs/vmlinux.h。 */
#ifndef PACC_STUB_BPF_HELPERS_H
#define PACC_STUB_BPF_HELPERS_H

#ifndef SEC
#define SEC(name) __attribute__((section(name), used))
#endif

#ifndef __always_inline
#define __always_inline inline __attribute__((always_inline))
#endif

/* 真实的 bpf_helpers.h 会定义 NULL（BPF 侧没有 <stddef.h>） */
#ifndef NULL
#define NULL ((void *)0)
#endif

#ifndef __uint
#define __uint(name, val) int (*name)[val]
#endif
#ifndef __type
#define __type(name, val) typeof(val) *name
#endif
#ifndef __array
#define __array(name, val) typeof(val) *name[]
#endif

#define BPF_ANY 0

/* BTF 定义的 map 结构体：__uint/__type 只是标记，真值是 libbpf 从 BTF 读的。
 * 桩里给它们一个不占空间的形态即可。 */
#define BPF_F_CURRENT_CPU 0xffffffffULL

/* helper 原型（与 bpf_helper_defs.h 同形：函数指针常量，值为 helper id） */
static void *(*bpf_map_lookup_elem)(void *map, const void *key) = (void *)1;
static long (*bpf_map_update_elem)(void *map, const void *key, const void *value,
				   __u64 flags) = (void *)2;
static __u64 (*bpf_ktime_get_ns)(void) = (void *)5;
static __u64 (*bpf_get_current_pid_tgid)(void) = (void *)14;
static __u64 (*bpf_get_current_uid_gid)(void) = (void *)15;
static long (*bpf_get_current_comm)(void *buf, __u32 size_of_buf) = (void *)16;
static long (*bpf_perf_event_output)(void *ctx, void *map, __u64 flags, void *data,
				     __u64 size) = (void *)25;
static __u64 (*bpf_get_current_task)(void) = (void *)35;
static long (*bpf_probe_read)(void *dst, __u32 size, const void *unsafe_ptr) = (void *)4;
static long (*bpf_probe_read_str)(void *dst, __u32 size, const void *unsafe_ptr) = (void *)45;
static long (*bpf_probe_read_user)(void *dst, __u32 size, const void *unsafe_ptr) = (void *)112;
static long (*bpf_probe_read_user_str)(void *dst, __u32 size, const void *unsafe_ptr) = (void *)114;
static long (*bpf_probe_read_kernel)(void *dst, __u32 size, const void *unsafe_ptr) = (void *)113;
static long (*bpf_ringbuf_output)(void *ringbuf, void *data, __u64 size, __u64 flags) = (void *)130;

#endif /* PACC_STUB_BPF_HELPERS_H */