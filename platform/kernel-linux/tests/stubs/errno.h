/* ⚠️ 语法检查用桩文件：在宿主机 <errno.h> 之上补齐 Linux 侧用到的宏 ⚠️
 * MSVC 的 errno.h 有 ENOSYS/EAGAIN，但没有 EWOULDBLOCK（Linux 上与 EAGAIN 等值）。
 * 真实 Linux 构建用系统 <errno.h>，桩只在 syntax_check.sh 里生效。 */
#ifndef PACC_STUB_ERRNO_H
#define PACC_STUB_ERRNO_H

#include_next <errno.h>

#ifndef EWOULDBLOCK
#define EWOULDBLOCK EAGAIN
#endif

#endif /* PACC_STUB_ERRNO_H */