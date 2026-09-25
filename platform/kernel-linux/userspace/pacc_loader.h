/* PACC eBPF loader —— 用户态侧接口（事件出口 + 事件编码）
 *
 * 两个用途：
 *   1. loader 主程序与 procfs 回退扫描器共用同一个事件出口（NDJSON over 管道/socket）；
 *   2. 与 linux-client 的消费契约写在一处（见 platform/linux-client/src/ebpf.rs
 *      里的字段说明），改这里就要同步那边。
 */
#ifndef PACC_LOADER_H
#define PACC_LOADER_H

#ifdef __cplusplus
extern "C" {
#endif

#include <unistd.h>

#include "pacc_ebpf.h"

/* 与 linux-client 的 pacc.client.ebpf.socket 默认值一致。
 * 改成别的路径必须两边同时改，否则客户端会一直等一个永远不出现的 socket。 */
#define PACC_DEFAULT_SOCKET "/run/pacc/pacc-ldm.sock"

/* 单行 NDJSON 的缓冲上限（loader 与 procfs 回退共用，必须是编译期常量）。
 * 2048 的依据：comm 16 + 路径 256 + detail 里的固定字段与转义膨胀，实测
 * 极值约 700 字节；留 3 倍余量避免「payload 长一点就整条丢」。 */
#define PACC_JSON_MAX 2048

/* 事件出口：stdout（调试/被 systemd 接管）或 UNIX 域流套接字（默认）。
 *
 * 为什么用 SOCK_STREAM + NDJSON 而不是 datagram：消费端要能看到「上一条事件到
 * 这一条的间隔」，流式套接字的背压会自然让 loader 感知到消费端卡住；datagram
 * 在消费端慢时是静默丢弃，而反作弊的静默丢事件等于没有检测。 */
struct pacc_sink {
	int listen_fd;		/* >=0: UNIX socket 监听；-1: 直接写 stdout */
	int client_fd;		/* >=0: 已连接的对端；-1: 未连接 */
	char path[108];		/* sun_path 上限 */
	unsigned long long lines;	/* 已成功写出的事件行数 */
	unsigned long long write_errors;	/* 写失败次数（对端断开等） */
};

/* use_stdout != 0 时忽略 sock_path，事件直接写 stdout。返回 0 成功。 */
int pacc_sink_init(struct pacc_sink *s, const char *sock_path, int use_stdout);

/* 有挂起连接就接一个（非阻塞）；已有连接或没有连接都不报错。 */
void pacc_sink_accept(struct pacc_sink *s);

/* 写一行（自动补 '\n'）。对端断开时丢连接并计数，不阻塞 loader。 */
void pacc_sink_emit(struct pacc_sink *s, const char *line, unsigned int len);

void pacc_sink_close(struct pacc_sink *s);

/* 事件 → 单行 NDJSON（不含换行）。返回写入长度，缓冲不足返回 -1。
 *
 * 字段与 linux-client 的宽容解析器对齐：event / pid / tgid / ppid / comm /
 * path / uid / severity / ts / ts_millis / detail。多余的字段（probe、
 * lat_ns、abi）它忽略，但排查问题时这些是唯一线索，所以照发。 */
int pacc_event_to_json(const struct pacc_event *e, char *out, unsigned int outsz);

/* 事件类型 → 上报用字符串（与 linux-client 的 is_suspicious 推断词表对齐）。 */
const char *pacc_event_name(__u32 type);
/* 事件类型 → severity（采集侧默认分级；用户态可覆盖）。 */
const char *pacc_event_severity(__u32 type);

/* 用户态侧的可疑名匹配：内核侧用的是「basename 前缀精确匹配 11 个名字」，这里
 * 用「整条路径子串匹配更全的名单」补上内核侧的漏网（长路径、带版本后缀、
 * scanmem/gameconqueror 等内核表里没有的名字）。命中返回 1 并填 hit_name。 */
int pacc_suspect_match_userspace(const char *path, const char **hit_name);

/* load_module 事件的固定说明：模块名无法在内核侧可靠取得（理由见
 * pacc-ebpf/load_module.bpf.c 的注释），这里把「该去哪儿补」写进事件里，
 * 免得消费端把空着的 module 字段当成 bug。 */
extern const char pacc_load_module_note[];

#ifdef __cplusplus
}
#endif

#endif /* PACC_LOADER_H */