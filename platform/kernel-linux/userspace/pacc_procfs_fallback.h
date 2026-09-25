/* PACC Linux procfs 回退扫描器（eBPF 不可用时的降级路径）
 *
 * 触发条件（由 pacc_capability 判定，见 pacc_capability.c）：
 *   内核 4.15~5.3（无 CO-RE）、内核缺 BTF、bpf() 被拒（无 CAP_BPF/root）、
 *   或 eBPF 对象在该内核上全部加载失败。
 *
 * 能力边界（说清楚，免得被当成「功能缩水」）：
 *   本模块只做 eBPF 路径**没有**对应物的那部分——枚举 /proc 找已经在跑的
 *   可疑进程。事件级监控（谁 execve 了、谁 ptrace 了）在回退路径上根本不存在，
 *   只能靠轮询后的「现状快照」，这是能力差异而不是实现差异，README 里也这么写。
 *
 * 与 platform/linux-client/src/procfs.rs 的关系：那份 Rust 实现是客户端自己的
 * 兜底（loader 没起来时用），侧重 maps 的 rwx/memfd/(deleted) 可执行映射分析；
 * 这里侧重「进程名/命令行命中工具特征」与 TracerPid。两者刻意不抄成一份：
 * 一个是进程现状快照，一个是内存布局分析，混在一起会让两边都变得难以解释。
 */
#ifndef PACC_PROCFS_FALLBACK_H
#define PACC_PROCFS_FALLBACK_H

#ifdef __cplusplus
extern "C" {
#endif

#include "pacc_loader.h"

/* 单轮扫描的统计（写日志用） */
struct pacc_procfs_stats {
	unsigned long scanned;		/* 实际检查过的进程数 */
	unsigned long findings;		/* 出事件的进程数 */
	unsigned long kernel_threads;	/* 跳过的内核线程数 */
};

/* 扫描一轮 /proc 并把发现以 NDJSON 写进 sink。
 * max_pids = 0 表示不限制；限制是为了在进程数上万的机器上让单轮耗时有上界。
 * 返回写出的发现条数，<0 表示 /proc 读不到（此时 stats->scanned == 0）。
 *
 * 事件契约：与 eBPF 路径同形（event / pid / comm / severity / ts / detail），
 * 但明确用 event="procfs_scan" 加 signature_hit 字段标出来源，消费端能一眼看
 * 出「这条不是内核事件，是轮询快照」，不会把它当成实时行为证据。 */
int pacc_procfs_scan_once(struct pacc_sink *sink, unsigned int max_pids,
			  struct pacc_procfs_stats *stats);

/* 本机是否可用 procfs（/proc 可读）。 */
int pacc_procfs_available(void);

#ifdef __cplusplus
}
#endif

#endif /* PACC_PROCFS_FALLBACK_H */