/* ⚠️ 语法检查用桩文件，不是 Linux 的 sys/utsname.h ⚠️ */
#ifndef PACC_STUB_SYS_UTSNAME_H
#define PACC_STUB_SYS_UTSNAME_H

struct utsname {
	char sysname[65];
	char nodename[65];
	char release[65];
	char version[65];
	char machine[65];
	char domainname[65];
};

int uname(struct utsname *buf);

#endif /* PACC_STUB_SYS_UTSNAME_H */