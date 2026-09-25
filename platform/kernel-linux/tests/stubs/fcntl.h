/* ⚠️ 语法检查用桩文件，不是 Linux 的 fcntl.h ⚠️ */
#ifndef PACC_STUB_FCNTL_H
#define PACC_STUB_FCNTL_H

#include <sys/types.h>

#define O_RDONLY   00
#define O_NONBLOCK 04000

#define F_GETFL 3
#define F_SETFL 4

int fcntl(int fd, int cmd, ...);

#endif /* PACC_STUB_FCNTL_H */