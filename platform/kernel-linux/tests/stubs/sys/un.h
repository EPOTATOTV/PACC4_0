/* ⚠️ 语法检查用桩文件，不是 Linux 的 sys/un.h ⚠️ */
#ifndef PACC_STUB_SYS_UN_H
#define PACC_STUB_SYS_UN_H

struct sockaddr_un {
	unsigned short sun_family;
	char sun_path[108];
};

#endif /* PACC_STUB_SYS_UN_H */