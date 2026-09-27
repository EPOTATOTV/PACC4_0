/* ⚠️ 语法检查用桩文件，不是 Linux 的 dirent.h ⚠️ */
#ifndef PACC_STUB_DIRENT_H
#define PACC_STUB_DIRENT_H

typedef struct pacc_stub_dir DIR;

struct dirent {
	unsigned long d_ino;
	unsigned char d_type;
	char d_name[256];
};

DIR *opendir(const char *name);
struct dirent *readdir(DIR *dirp);
int closedir(DIR *dirp);

#endif /* PACC_STUB_DIRENT_H */