/*
 * PACC Linux 用户态守护进程
 * 功能：配置加载 -> 周期扫描 /proc 定位受保护游戏进程 ->
 *       读取内核模块 debugfs 统计（/sys/kernel/debug/pacc/stats）-> 事件聚合 ->
 *       经 HTTP POST 上报 PTV（或输出 JSON 行供 ptv-client 消费）。
 * 纯 libc，无第三方依赖；编译：make
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <dirent.h>
#include <time.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <stdbool.h>

#define PACC_CONFIG_PATH  "/etc/pacc/pacc-daemon.conf"
#define PACC_DEBUGFS_STATS "/sys/kernel/debug/pacc/stats"

static volatile sig_atomic_t g_running = 1;
static void on_signal(int sig) { (void)sig; g_running = 0; }

/* 配置 */
struct cfg {
    char pteid[64];
    char edition[32];
    char server[256];      /* 如 http://ptv:8080/api/player/events */
    char secret[128];
    int  interval_sec;
    int  max_protected;
};

static void cfg_defaults(struct cfg *c) {
    strncpy(c->pteid, "PT0000000001", sizeof(c->pteid) - 1);
    strncpy(c->edition, "JAVA", sizeof(c->edition) - 1);
    strncpy(c->server, "http://127.0.0.1:8080/api/player/events", sizeof(c->server) - 1);
    strncpy(c->secret, "", sizeof(c->secret) - 1);
    c->interval_sec = 15;
    c->max_protected = 64;
}

static void trim(char *s) {
    char *p = s + strlen(s);
    while (p > s && (p[-1] == '\n' || p[-1] == '\r' || p[-1] == ' ')) p--;
    *p = '\0';
}

static void cfg_load(struct cfg *c, const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) { fprintf(stderr, "[PACC-linux] no config %s, use defaults\n", path); return; }
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        char key[128], val[384];
        trim(line);
        if (line[0] == '#' || line[0] == '\0') continue;
        if (sscanf(line, "%127[^=]=%383[^\n]", key, val) == 2) {
            trim(val);
            if      (strcmp(key, "pteid") == 0)           strncpy(c->pteid, val, sizeof(c->pteid) - 1);
            else if (strcmp(key, "edition") == 0)         strncpy(c->edition, val, sizeof(c->edition) - 1);
            else if (strcmp(key, "server") == 0)          strncpy(c->server, val, sizeof(c->server) - 1);
            else if (strcmp(key, "secret") == 0)          strncpy(c->secret, val, sizeof(c->secret) - 1);
            else if (strcmp(key, "interval_sec") == 0)    c->interval_sec = atoi(val);
            else if (strcmp(key, "max_protected") == 0)   c->max_protected = atoi(val);
        }
    }
    fclose(f);
}

/* 从 /proc/<pid>/comm 读取进程名 */
static int proc_name(pid_t pid, char *name, size_t len) {
    char p[64];
    int fd;
    snprintf(p, sizeof(p), "/proc/%d/comm", pid);
    fd = open(p, O_RDONLY);
    if (fd < 0) return -1;
    ssize_t n = read(fd, name, len - 1);
    close(fd);
    if (n <= 0) return -1;
    name[n] = '\0';
    trim(name);
    return 0;
}

/* 读取内核模块命中统计 */
static int read_kernel_stats(long long *ptrace, long long *pvmread, long long *devmem) {
    char buf[256];
    int fd = open(PACC_DEBUGFS_STATS, O_RDONLY);
    if (fd < 0) return -1;
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    return sscanf(buf, "ptrace=%lld pvmread=%lld devmem_write=%lld",
                  ptrace, pvmread, devmem) == 3 ? 0 : -1;
}

/* 极简 HTTP POST / JSON 构造（无依赖） */
static int http_post(const char *url, const char *json) {
    char host[256] = {0};
    int  port = 80;
    const char *path = NULL;
    /* 解析 http://host[:port]/path */
    if (strncmp(url, "http://", 7) != 0) return -1;
    const char *p = url + 7;
    const char *slash = strchr(p, '/');
    if (slash) {
        size_t hl = (size_t)(slash - p);
        if (hl >= sizeof(host)) hl = sizeof(host) - 1;
        memcpy(host, p, hl);
        path = slash;
    } else {
        strncpy(host, p, sizeof(host) - 1);
        path = "/";
    }
    char *colon = strchr(host, ':');
    if (colon) { *colon = '\0'; port = atoi(colon + 1); }

    struct addrinfo hints = {0}, *res = NULL;
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    char portstr[8];
    snprintf(portstr, sizeof(portstr), "%d", port);
    if (getaddrinfo(host, portstr, &hints, &res) != 0) return -1;

    int fd = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (fd < 0) { freeaddrinfo(res); return -1; }
    if (connect(fd, res->ai_addr, res->ai_addrlen) != 0) {
        close(fd); freeaddrinfo(res); return -1;
    }
    freeaddrinfo(res);

    char head[1024];
    int hlen = snprintf(head, sizeof(head),
        "POST %s HTTP/1.1\r\n"
        "Host: %s\r\n"
        "Content-Type: application/json\r\n"
        "Content-Length: %zu\r\n"
        "Connection: close\r\n"
        "\r\n", path, host, strlen(json));
    send(fd, head, hlen, 0);
    send(fd, json, strlen(json), 0);
    close(fd);
    return 0;
}

/* 聚合一次上报 JSON 并 POST 到 PTV */
static void report(const struct cfg *c, pid_t target,
                   long long pt, long long pv, long long dm) {
    time_t now = time(NULL);
    char body[2048];
    snprintf(body, sizeof(body),
        "{\"pteid\":\"%s\",\"edition\":\"%s\",\"proto\":\"linux-daemon\","
        "\"protected_pid\":%d,\"hits\":{\"ptrace\":%lld,\"pvmread\":%lld,\"devmem_write\":%lld},"
        "\"ts\":%ld}",
        c->pteid, c->edition, (int)target, pt, pv, dm, (long)now);
    if (http_post(c->server, body) == 0) {
        fprintf(stderr, "[PACC-linux] reported %ld bytes\n", (long)strlen(body));
    } else {
        /* 无服务端时输出 JSON 行供 ptv-client 本地消费 */
        fprintf(stdout, "%s\n", body);
    }
}

int main(int argc, char **argv) {
    struct cfg c;
    cfg_defaults(&c);
    const char *conf = argc > 1 ? argv[1] : PACC_CONFIG_PATH;
    cfg_load(&c, conf);

    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);

    fprintf(stderr, "[PACC-linux] daemon start pteid=%s server=%s interval=%ds\n",
            c.pteid, c.server, c.interval_sec);

    while (g_running) {
        /* 1) 扫描 /proc 精确定位受保护游戏进程（按 comm 需匹配；展示取当前） */
        pid_t target = 0;
        DIR *d = opendir("/proc");
        if (d) {
            struct dirent *e;
            while ((e = readdir(d)) != NULL) {
                if (e->d_name[0] && e->d_name[0] >= '0' && e->d_name[0] <= '9') {
                    pid_t pid = atoi(e->d_name);
                    char nm[128];
                    if (proc_name(pid, nm, sizeof(nm)) == 0) {
                        if (strcmp(nm, "java") == 0 || strncmp(nm, "javaw", 5) == 0 ||
                            strcmp(nm, "Minecraft.Windows") == 0) {
                            target = pid; /* 绑定首个匹配 */
                            break;
                        }
                    }
                }
            }
            closedir(d);
        }

        /* 2) 读取内核模块统计 */
        long long pt = 0, pv = 0, dm = 0;
        int has_mod = read_kernel_stats(&pt, &pv, &dm) == 0;

        /* 3) 上报 */
        if (target > 0 || has_mod)
            report(&c, target, pt, pv, dm);

        sleep((unsigned)(c.interval_sec < 1 ? 1 : c.interval_sec));
    }
    fprintf(stderr, "[PACC-linux] daemon exit\n");
    return 0;
}