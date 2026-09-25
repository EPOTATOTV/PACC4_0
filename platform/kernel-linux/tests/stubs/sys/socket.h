/* ⚠️ 语法检查用桩文件，不是 Linux 的 sys/socket.h ⚠️ */
#ifndef PACC_STUB_SYS_SOCKET_H
#define PACC_STUB_SYS_SOCKET_H

#include <sys/types.h>

typedef unsigned int socklen_t;

struct sockaddr {
	unsigned short sa_family;
	char sa_data[14];
};

#define AF_UNIX 1
#define SOCK_STREAM 1
#define SOL_SOCKET 1
#define SO_REUSEADDR 2
#define MSG_NOSIGNAL 0x4000

int socket(int domain, int type, int protocol);
int bind(int sockfd, const struct sockaddr *addr, socklen_t addrlen);
int listen(int sockfd, int backlog);
int accept(int sockfd, struct sockaddr *addr, socklen_t *addrlen);
ssize_t send(int sockfd, const void *buf, size_t len, int flags);
int setsockopt(int sockfd, int level, int optname, const void *optval, socklen_t optlen);

#endif /* PACC_STUB_SYS_SOCKET_H */