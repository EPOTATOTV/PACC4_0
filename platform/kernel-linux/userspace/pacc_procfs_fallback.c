/* PACC Linux procfs 回退扫描器实现
 *
 * 只在 eBPF 路径不可用时被 pacc_ebpf_loader 调用（判定见 pacc_capability.c）。
 * 独立一个编译单元而不是塞进 loader：它不依赖 libbpf，可以单独做语法检查，
 * 也能在 4.15 的老机器上单独编出来验证。
 */
#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/stat.h>

#include "pacc_json.h"
#include "pacc_procfs_fallback.h"

/* 与 platform/linux-client/src/procfs.rs 的两张表保持同源（那边是客户端在
 * loader 没起来时的兜底）。刻意分开维护而不是抽公共表：一个 Rust 一个 C，
 * 跨语言共享名单要么引入构建期代码生成、要么手工同步；分开至少出错时能一眼
 * 看出是「名单不同步」而不是「检测逻辑坏了」。改这里请顺手改那边。 */
struct pacc_proc_sig {
	const char *needle;
	const char *signature;
	const char *event;
	const char *severity;
};

static const struct pacc_proc_sig pacc_cheat_sigs[] = {
	{ "xray", "xray", "known_cheat_tool", "high" },
	{ "wurst", "wurst", "known_cheat_tool", "high" },
	{ "meteor", "meteor_client", "known_cheat_tool", "high" },
	{ "impact", "impact", "known_cheat_tool", "high" },
	{ "aristois", "aristois", "known_cheat_tool", "high" },
	{ "baritone", "baritone", "known_cheat_tool", "high" },
	{ "killaura", "killaura", "known_cheat_tool", "high" },
	{ "aimbot", "aimbot", "known_cheat_tool", "high" },
	{ "autoclicker", "autoclicker", "known_cheat_tool", "high" },
	{ "cheatengine", "cheat_engine", "known_cheat_tool", "high" },
	{ "scanmem", "cheat_engine", "known_cheat_tool", "high" },
	{ "gameconqueror", "cheat_engine", "known_cheat_tool", "high" },
	{ "frida", "frida", "known_cheat_tool", "high" },
	{ "dobby", "dobby_hook", "known_cheat_tool", "high" },
	{ "substrate", "substrate_hook", "known_cheat_tool", "high" },
	{ "xposed", "xposed", "known_cheat_tool", "high" },
	{ "linux-inject", "process_injector", "known_cheat_tool", "high" },
};

static const struct pacc_proc_sig pacc_debug_sigs[] = {
	{ "gdb", "gdb", "debug_tool", "medium" },
	{ "lldb", "lldb", "debug_tool", "medium" },
	{ "ptrace", "ptrace_tool", "debug_tool", "medium" },
	{ "strace", "strace", "debug_tool", "medium" },
	{ "ltrace", "ltrace", "debug_tool", "medium" },
	{ "radare2", "radare2", "debug_tool", "medium" },
	{ "ghidra", "ghidra", "debug_tool", "medium" },
	{ "ida64", "ida", "debug_tool", "medium" },
	{ "ida32", "ida", "debug_tool", "medium" },
};

#define PACC_CHEAT_SIG_COUNT (sizeof(pacc_cheat_sigs) / sizeof(pacc_cheat_sigs[0]))
#define PACC_DEBUG_SIG_COUNT (sizeof(pacc_debug_sigs) / sizeof(pacc_debug_sigs[0]))

#define PACC_COMM_MAX    64	/* 内核 TASK_COMM_LEN 是 16，留足余量 */
#define PACC_CMDLINE_MAX 512
#define PACC_EXE_MAX     256
/* 特征匹配用的合并串：comm + exe + cmdline。上界必须是编译期常量（栈数组）。 */
#define PACC_BLOB_MAX    (PACC_COMM_MAX + PACC_EXE_MAX + PACC_CMDLINE_MAX + 8)

/* 每个进程最多出一条发现：一个进程名同时命中多个特征词时，只报最严重的那条。
 * 报多条会让同一进程在事件流里出现好几次，聚合时会被当成多个作弊进程。 */
struct pacc_proc_hit {
	const char *signature;
	const char *event;
	const char *severity;
};

static const struct pacc_proc_hit *pacc_match_signature(const char *blob,
						       struct pacc_proc_hit *slot)
{
	char lower[PACC_BLOB_MAX];
	size_t i;

	if (!blob || !slot)
		return NULL;
	snprintf(lower, sizeof(lower), "%s", blob);
	for (i = 0; lower[i]; i++)
		lower[i] = (char)tolower((unsigned char)lower[i]);

	for (i = 0; i < PACC_CHEAT_SIG_COUNT; i++) {
		if (strstr(lower, pacc_cheat_sigs[i].needle)) {
			slot->signature = pacc_cheat_sigs[i].signature;
			slot->event = pacc_cheat_sigs[i].event;
			slot->severity = pacc_cheat_sigs[i].severity;
			return slot;
		}
	}
	for (i = 0; i < PACC_DEBUG_SIG_COUNT; i++) {
		if (strstr(lower, pacc_debug_sigs[i].needle)) {
			slot->signature = pacc_debug_sigs[i].signature;
			slot->event = pacc_debug_sigs[i].event;
			slot->severity = pacc_debug_sigs[i].severity;
			return slot;
		}
	}
	return NULL;
}

/* 读 /proc/<pid>/<file> 的一行到 out（失败时 out 为空串）。 */
static int pacc_read_line(const char *path, char *out, unsigned int outsz)
{
	FILE *f;
	size_t n;

	if (outsz == 0)
		return 0;
	out[0] = '\0';
	f = fopen(path, "r");
	if (!f)
		return 0;
	if (!fgets(out, (int)outsz, f)) {
		fclose(f);
		out[0] = '\0';
		return 0;
	}
	fclose(f);
	n = strlen(out);
	while (n > 0 && (out[n - 1] == '\n' || out[n - 1] == '\r'))
		out[--n] = '\0';
	return 1;
}

/* /proc/<pid>/cmdline 是 NUL 分隔的；转成空格分隔便于做子串匹配。 */
static void pacc_read_cmdline(pid_t pid, char *out, unsigned int outsz)
{
	char path[64];
	FILE *f;
	unsigned int n = 0;
	int c;

	if (outsz == 0)
		return;
	out[0] = '\0';
	snprintf(path, sizeof(path), "/proc/%d/cmdline", (int)pid);
	f = fopen(path, "r");
	if (!f)
		return;
	while (n + 1 < outsz && (c = fgetc(f)) != EOF)
		out[n++] = (c == '\0') ? ' ' : (char)c;
	out[n] = '\0';
	fclose(f);
}

static void pacc_read_exe(pid_t pid, char *out, unsigned int outsz)
{
	char path[64];
	ssize_t n;

	if (outsz == 0)
		return;
	out[0] = '\0';
	snprintf(path, sizeof(path), "/proc/%d/exe", (int)pid);
	n = readlink(path, out, outsz - 1);
	if (n <= 0) {
		out[0] = '\0';
		return;
	}
	out[n] = '\0';
	/* 已被删除的可执行文件读出来带 " (deleted)" 后缀，去掉便于归因 */
	{
		char *p = strstr(out, " (deleted)");

		if (p)
			*p = '\0';
	}
}

/* uid 用 stat("/proc/<pid>") 取：/proc/<pid> 的所有者是进程的真实 uid，
 * 比读 status 再解析省一次文件读和一次字符串解析。 */
static unsigned int pacc_proc_uid(pid_t pid)
{
	char path[64];
	struct stat st;

	snprintf(path, sizeof(path), "/proc/%d", (int)pid);
	if (stat(path, &st) != 0)
		return 0;
	return (unsigned int)st.st_uid;
}

static int pacc_emit_finding(struct pacc_sink *sink, pid_t pid, const char *comm,
			     const char *exe, const char *cmdline, const char *signature,
			     const char *event, const char *severity, unsigned int uid)
{
	char line[PACC_JSON_MAX];
	struct pacc_jbuf b;
	int n;

	memset(&b, 0, sizeof(b));
	b.buf = line;
	b.cap = sizeof(line);
	j_raw(&b, "{", 1);
	j_kv_str(&b, "event", event);
	/* source 字段是这条路径存在的意义：消费端必须能区分「内核实时事件」与
	 * 「轮询快照」。同一份 NDJSON 流里混着两类证据，不标出来会被当成实时行为。 */
	j_kv_str(&b, "source", "procfs-fallback");
	j_kv_u64(&b, "pid", (unsigned long long)pid);
	j_kv_str(&b, "comm", comm);
	if (exe && exe[0])
		j_kv_str(&b, "path", exe);
	j_kv_str(&b, "severity", severity);
	j_kv_u64(&b, "uid", uid);
	j_kv_u64(&b, "ts", (unsigned long long)time(NULL));
	j_key(&b, "detail");
	j_raw(&b, "{", 1);
	j_kv_str(&b, "signature_hit", signature);
	j_kv_str(&b, "cmdline", cmdline);
	j_kv_str(&b, "note", "procfs 轮询快照，非实时行为证据");
	j_raw(&b, "}", 1);
	j_raw(&b, "}", 1);

	n = pacc_jbuf_finish(&b);
	if (n < 0) {
		fprintf(stderr, "[PACC] procfs 发现的事件过长，丢弃\n");
		return 0;
	}
	pacc_sink_accept(sink);
	pacc_sink_emit(sink, line, (unsigned int)n);
	return 1;
}

/* 自身是否被调试：读 /proc/self/status 找 TracerPid。
 * 这是唯一不需要遍历就能拿到的「本进程正在被调试」证据，成本几乎为零。 */
static void pacc_check_self_tracer(struct pacc_sink *sink)
{
	char status[4096];
	char line[PACC_JSON_MAX];
	struct pacc_jbuf b;
	char *p;
	size_t got;
	unsigned long tracer;
	FILE *f;
	int n;

	f = fopen("/proc/self/status", "r");
	if (!f)
		return;
	got = fread(status, 1, sizeof(status) - 1, f);
	fclose(f);
	status[got] = '\0';

	p = strstr(status, "TracerPid:");
	if (!p)
		return;
	tracer = strtoul(p + 10, NULL, 10);
	if (tracer == 0)
		return;

	memset(&b, 0, sizeof(b));
	b.buf = line;
	b.cap = sizeof(line);
	j_raw(&b, "{", 1);
	j_kv_str(&b, "event", "debugger_attached");
	j_kv_str(&b, "source", "procfs-fallback");
	j_kv_u64(&b, "pid", (unsigned long long)getpid());
	j_kv_u64(&b, "tracer_pid", (unsigned long long)tracer);
	j_kv_str(&b, "severity", "high");
	j_kv_u64(&b, "ts", (unsigned long long)time(NULL));
	j_raw(&b, "}", 1);
	n = pacc_jbuf_finish(&b);
	if (n > 0) {
		pacc_sink_accept(sink);
		pacc_sink_emit(sink, line, (unsigned int)n);
	}
}

int pacc_procfs_available(void)
{
	DIR *d = opendir("/proc");

	if (!d)
		return 0;
	closedir(d);
	return 1;
}

int pacc_procfs_scan_once(struct pacc_sink *sink, unsigned int max_pids,
			  struct pacc_procfs_stats *stats)
{
	DIR *d;
	struct dirent *ent;
	pid_t self = getpid();
	unsigned int checked = 0;
	int findings = 0;

	if (stats)
		memset(stats, 0, sizeof(*stats));

	d = opendir("/proc");
	if (!d)
		return -1;

	while ((ent = readdir(d)) != NULL) {
		char comm[PACC_COMM_MAX];
		char cmdline[PACC_CMDLINE_MAX];
		char exe[PACC_EXE_MAX];
		char blob[PACC_BLOB_MAX];
		char ppath[64];
		struct pacc_proc_hit slot;
		const struct pacc_proc_hit *hit;
		pid_t pid;
		char *end;

		if (ent->d_name[0] < '0' || ent->d_name[0] > '9')
			continue;
		pid = (pid_t)strtol(ent->d_name, &end, 10);
		if (*end != '\0' || pid <= 0)
			continue;
		if (pid == self)	/* 别把自己判成作弊工具 */
			continue;
		if (max_pids && checked >= max_pids)
			break;
		checked++;

		snprintf(ppath, sizeof(ppath), "/proc/%d/comm", (int)pid);
		pacc_read_line(ppath, comm, sizeof(comm));
		pacc_read_exe(pid, exe, sizeof(exe));
		pacc_read_cmdline(pid, cmdline, sizeof(cmdline));

		/* 内核线程：没有用户态映像（exe 取不到）且命令行为空。
		 * 跳过它们能省掉大量无意义的字符串匹配——进程数里有相当比例是内核线程。 */
		if (exe[0] == '\0' && cmdline[0] == '\0') {
			if (stats)
				stats->kernel_threads++;
			continue;
		}
		if (stats)
			stats->scanned++;

		snprintf(blob, sizeof(blob), "%s %s %s", comm, exe, cmdline);
		memset(&slot, 0, sizeof(slot));
		hit = pacc_match_signature(blob, &slot);
		if (!hit)
			continue;

		if (pacc_emit_finding(sink, pid, comm, exe, cmdline, hit->signature,
				      hit->event, hit->severity, pacc_proc_uid(pid)))
			findings++;
	}
	closedir(d);

	/* 本进程的调试通道单独查一次：它不是「某个进程可疑」，而是
	 * 「我们自己正在被看着」，性质不同，所以用独立事件名与 severity。 */
	pacc_check_self_tracer(sink);

	if (stats)
		stats->findings = (unsigned long)findings;
	return findings;
}