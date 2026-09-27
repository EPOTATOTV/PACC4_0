/* ⚠️ 语法检查用桩文件，不是 libbpf 的 bpf/bpf.h ⚠️ */
#ifndef PACC_STUB_BPF_BPF_H
#define PACC_STUB_BPF_BPF_H

#include <linux/bpf.h>

int bpf(int cmd, union bpf_attr *attr);
int bpf_map_update_elem(int fd, const void *key, const void *value, __u64 flags);

#endif /* PACC_STUB_BPF_BPF_H */