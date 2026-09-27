/* ⚠️ 语法检查用桩文件，不是 libbpf 的真 bpf_core_read.h ⚠️
 * 真实实现用 __builtin_preserve_access_index + bpf_probe_read_kernel 做字段
 * 重定位；这里的 __builtin_preserve_access_index 是 clang 内建（-target bpf 下
 * 可用），所以这层与真实实现的形态一致，能查出误用。 */
#ifndef PACC_STUB_BPF_CORE_READ_H
#define PACC_STUB_BPF_CORE_READ_H

#define bpf_core_read(dst, sz, src)						\
	bpf_probe_read_kernel((dst), (sz),					\
			      (const void *)__builtin_preserve_access_index(src))

#define bpf_core_read_str(dst, sz, src)						\
	bpf_probe_read_kernel_str((dst), (sz),					\
				  (const void *)__builtin_preserve_access_index(src))

#endif /* PACC_STUB_BPF_CORE_READ_H */