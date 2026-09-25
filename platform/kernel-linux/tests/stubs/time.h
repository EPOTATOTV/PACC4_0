/* ⚠️ 语法检查用桩文件：在宿主机（Windows/MSVC）的 <time.h> 之上补 POSIX 部分 ⚠️
 *
 * 为什么用 #include_next 而不是重写 time_t：redefining time_t 在多种平台上都会
 * 与真实头冲突（MSVC 是 __time64_t=long long，glibc 是 long）。所以先取真实的
 * <time.h>，只补它缺的 POSIX 声明。
 * 真实 Linux 构建用系统 <time.h>（本身就带这些声明），桩只在 syntax_check.sh 里生效。 */
#ifndef PACC_STUB_TIME_H
#define PACC_STUB_TIME_H

#include_next <time.h>

#ifndef CLOCK_REALTIME
#define CLOCK_REALTIME 0
#endif
#ifndef CLOCK_MONOTONIC
#define CLOCK_MONOTONIC 1
#endif

int clock_gettime(int clockid, struct timespec *tp);

#endif /* PACC_STUB_TIME_H */