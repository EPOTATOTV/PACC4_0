/* ⚠️ 语法检查用桩文件，不是 Linux 的 unistd.h ⚠️ */
#ifndef PACC_STUB_UNISTD_H
#define PACC_STUB_UNISTD_H

#include <sys/types.h>

#define F_OK 0
#define R_OK 4
#define W_OK 2

uid_t geteuid(void);
gid_t getegid(void);
pid_t getpid(void);
int close(int fd);
int access(const char *pathname, int mode);
int unlink(const char *pathname);
int usleep(unsigned int usec);
ssize_t readlink(const char *pathname, char *buf, size_t bufsiz);
ssize_t read(int fd, void *buf, size_t count);
ssize_t write(int fd, const void *buf, size_t count);

#endif /* PACC_STUB_UNISTD_H */