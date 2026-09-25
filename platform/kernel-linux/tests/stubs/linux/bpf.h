/* ⚠️ 语法检查用桩文件，不是 Linux 的 linux/bpf.h ⚠️
 * 只保留 userspace/pacc_capability.c 做「bpf(2) 可用性实测」时用到的部分：
 * BPF_MAP_CREATE 与 union bpf_attr 里的几个字段。
 * 真实构建用系统的 <linux/bpf.h>（libbpf 依赖它保持 ABI 对齐）。 */
#ifndef PACC_STUB_LINUX_BPF_H
#define PACC_STUB_LINUX_BPF_H

/* 与内核 include/uapi/asm-generic/int-ll64.h 的写法一致：必须与
 * pacc_ebpf.h 用户态分支的 typedef 完全同型，否则同一 TU 里 include 两者会
 * 因「不同型 typedef 重定义」报错。 */
typedef unsigned char __u8;
typedef unsigned short __u16;
typedef unsigned int __u32;
typedef unsigned long long __u64;

enum bpf_cmd {
	BPF_MAP_CREATE = 0,
	BPF_MAP_LOOKUP_ELEM = 1,
	BPF_MAP_UPDATE_ELEM = 2,
};

enum bpf_map_type {
	BPF_MAP_TYPE_UNSPEC = 0,
	BPF_MAP_TYPE_HASH = 1,
	BPF_MAP_TYPE_ARRAY = 2,
	BPF_MAP_TYPE_PROG_ARRAY = 3,
	BPF_MAP_TYPE_PERF_EVENT_ARRAY = 4,
	BPF_MAP_TYPE_PERCPU_ARRAY = 6,
	BPF_MAP_TYPE_PERCPU_HASH = 5,
	BPF_MAP_TYPE_RINGBUF = 27,
};

union bpf_attr {
	struct {
		__u32 map_type;
		__u32 key_size;
		__u32 value_size;
		__u32 max_entries;
		__u32 map_flags;
		__u32 inner_map_fd;
		__u32 numa_node;
		char map_name[16];
		__u32 map_ifindex;
		__u32 btf_fd;
		__u32 btf_key_type_id;
		__u32 btf_value_type_id;
	};
};

#define BPF_ANY 0

#endif /* PACC_STUB_LINUX_BPF_H */