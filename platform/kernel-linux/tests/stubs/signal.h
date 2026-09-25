/* ⚠️ 语法检查用桩文件，不是 Linux 的 signal.h ⚠️ */
#ifndef PACC_STUB_SIGNAL_H
#define PACC_STUB_SIGNAL_H

#ifdef __cplusplus
extern "C" {
#endif

typedef int sig_atomic_t;
typedef void (*pacc_sighandler_t)(int);

#define SIGHUP  1
#define SIGINT  2
#define SIGPIPE 13
#define SIGTERM 15

#define SIG_DFL ((pacc_sighandler_t)0)
#define SIG_IGN ((pacc_sighandler_t)1)

pacc_sighandler_t signal(int signum, pacc_sighandler_t handler);

#ifdef __cplusplus
}
#endif

#endif /* PACC_STUB_SIGNAL_H */