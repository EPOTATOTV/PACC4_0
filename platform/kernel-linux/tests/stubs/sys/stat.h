/* ⚠️ 语法检查用桩文件，不是 Linux 的 sys/stat.h ⚠️ */
#ifndef PACC_STUB_SYS_STAT_H
#define PACC_STUB_SYS_STAT_H

#include <sys/types.h>

struct stat {
	dev_t st_dev;
	ino_t st_ino;
	mode_t st_mode;
	unsigned int st_nlink;
	uid_t st_uid;
	gid_t st_gid;
	off_t st_size;
	long st_blksize;
	long st_blocks;
};

#define S_IFMT  0170000
#define S_IFSOCK 0140000
#define S_ISSOCK(m) (((m) & S_IFMT) == S_IFSOCK)

int stat(const char *pathname, struct stat *statbuf);
int mkdir(const char *pathname, mode_t mode);

#endif /* PACC_STUB_SYS_STAT_H */