/* ⚠️ 语法检查用桩文件，不是 glibc 的 sys/syscall.h ⚠️
 *
 * 只补 userspace/pacc_capability.c 直接调 syscall(__NR_bpf) 需要的两样东西：
 * syscall() 的原型，以及 __NR_bpf。真实的 Linux 上这些由 glibc 提供，但本仓库的
 * 语法检查刻意不要求 Linux 头文件（见 syntax_check.sh 顶部说明），macOS 等宿主
 * 也没有 __NR_bpf，所以在这里兜住。
 *
 * 321 是 x86_64 的号（arm64 是 280、i386 是 357……），这里**只影响语法检查**：
 * 真实构建不会用桩头文件，拿的是系统 <sys/syscall.h> 里与架构一致的值。
 */
#ifndef PACC_STUB_SYS_SYSCALL_H
#define PACC_STUB_SYS_SYSCALL_H

#ifndef __NR_bpf
#define __NR_bpf 321
#endif

extern long syscall(long __number, ...);

#endif /* PACC_STUB_SYS_SYSCALL_H */