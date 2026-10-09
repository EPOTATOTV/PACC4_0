/* ⚠️ 语法检查用桩文件，不是 libbpf 的 bpf/bpf.h ⚠️ */
#ifndef PACC_STUB_BPF_BPF_H
#define PACC_STUB_BPF_BPF_H

#include <linux/bpf.h>

/* 这里刻意**不**声明裸 bpf()：真实的 <bpf/bpf.h> 里也没有它（libbpf 只给
 * bpf_map_create() 这类高阶 API）。之前这里放了个签名对不上的假 bpf()，
 * 让 syntax_check 放过了 userspace/pacc_capability.c 里的隐式声明，
 * 到真实构建（GCC 13 + -Werror=implicit-function-declaration）才炸。 */
int bpf_map_update_elem(int fd, const void *key, const void *value, __u64 flags);

#endif /* PACC_STUB_BPF_BPF_H */