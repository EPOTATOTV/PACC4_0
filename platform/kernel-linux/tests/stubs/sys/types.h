/* ⚠️ 语法检查用桩文件，不是 Linux 的 sys/types.h ⚠️
 * 只声明 userspace 下的 .c 用到的类型。详见 tests/stubs/vmlinux.h 顶部说明。
 * 注意：故意不定义 time_t（本机 clang 的 <time.h> 已定义且类型不同，重定义会报错），
 * 需要 time_t 的地方直接 include <time.h>。 */
#ifndef PACC_STUB_SYS_TYPES_H
#define PACC_STUB_SYS_TYPES_H

#include <stddef.h>
#include <time.h>

typedef int pid_t;
typedef long ssize_t;
typedef unsigned int mode_t;
typedef long off_t;
typedef unsigned int uid_t;
typedef unsigned int gid_t;
typedef unsigned long dev_t;
typedef unsigned long ino_t;

#endif /* PACC_STUB_SYS_TYPES_H */