/* PACC eBPF 用户态 loader
 *
 * 职责：探测能力 → 按内核版本挑对象变体 → 逐个挂载点 open/load/attach（互不牵连）
 *       → 轮询 ring/perf buffer → 解码成 NDJSON → 写 stdout 或 UNIX socket。
 *       eBPF 走不通就切 procfs 轮询（pacc_procfs_fallback.c）。
 *
 * 设计上的三条硬规矩：
 *   1. **逐挂载点隔离**。某个挂载点在内核上挂不上（符号改名、内核未编该 tracepoint、
 *      LSM 拦住），只丢它一个，其余照常工作，并在日志里如实说清丢的是哪个。
 *      整批 abort 会让「少了一个挂载点」变成「采集全停」，这是两件严重性差很多的事。
 *   2. **失败要能归因**。所有失败路径都给出 errno + 一句人话，并把 libbpf 最后一条
 *      告警（含校验器日志尾巴）一并打出来。反作弊现场最难的是「为什么这台机器没数据」。
 *   3. **不伪造**。挂载点没挂上就绝不假装在监控；procfs 回退产生的事件用独立
 *      event 名标出来源，消费端能区分「实时行为」和「轮询快照」。
 */
#define _GNU_SOURCE
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>

#include <bpf/bpf.h>
#include <bpf/libbpf.h>
#include <bpf/libbpf_version.h>

#include "pacc_ebpf.h"
#include "pacc_loader.h"
#include "pacc_capability.h"
#include "pacc_procfs_fallback.h"
#include "pacc_json.h"

/* ============================ 常量与全局 ============================ */

#define PACC_MAX_OBJECTS 6
#define PACC_MAX_LINKS   8
#define PACC_MAX_PIDS    64
#define PACC_POLL_MS     200
#define PACC_PERF_POLL_MS 20
#define PACC_PERF_PAGES  8
#define PACC_STATS_SEC   60
#define PACC_PROC_INTERVAL 15

#define PACC_PROC_UNSUPPORTED \
	"procfs 回退扫描仅在 Linux 可用（本进程不是跑在 Linux 上，或 /proc 未挂载）"

static volatile sig_atomic_t g_stop;
static volatile sig_atomic_t g_reload;
static int g_verbose;
static int g_capture_all_openat;
static int g_capture_all_execve = 1;
static pid_t g_pids[PACC_MAX_PIDS];
static int g_npids;

/* 每个挂载点的事件计数：验证 A11（能否捕获 execve/ptrace）时就是看这几个数 */
static unsigned long long g_evt_count[PACC_EV_MAX + 1];
static unsigned long long g_evt_total;
static time_t g_last_stats;

/* ============================ libbpf 日志与错误归因 ============================ */

#define PACC_LASTERR_MAX 512
static char g_lasterr[PACC_LASTERR_MAX];

static void pacc_set_lasterr(const char *fmt, ...)
{
	va_list ap;

	va_start(ap, fmt);
	vsnprintf(g_lasterr, sizeof(g_lasterr), fmt, ap);
	va_end(ap);
}

static int pacc_libbpf_print(enum libbpf_print_level level, const char *format, va_list args)
{
	char buf[512];
	int n;

	if (level == LIBBPF_DEBUG && !g_verbose)
		return 0;
	n = vsnprintf(buf, sizeof(buf), format, args);
	if (n < 0)
		return 0;
	/* 留下最后一条 WARN/ERR 作为失败原因：校验器日志很长，
	 * 但真正说明问题的是最后那一句 "invalid ..." / "R%d ..."。 */
	if (level <= LIBBPF_WARN) {
		size_t l = strlen(buf);

		while (l > 0 && (buf[l - 1] == '\n' || buf[l - 1] == '\r'))
			buf[--l] = '\0';
		snprintf(g_lasterr, sizeof(g_lasterr), "%s", buf);
	}
	fputs(buf, stderr);
	return n;
}

/* ============================ 用户态侧可疑名表 ============================ */

/* 内核侧（execve.bpf.c）只做 basename 前缀匹配 11 个名字；这里用整条路径的子串
 * 匹配更全的名单，补两类漏网：路径里带目录的工具（/opt/ghidra/ghidraRun）、
 * 内核表里没列但现网见过的（scanmem / gameconqueror），以及带很长的版本后缀的。 */
struct pacc_us_suspect {
	const char *needle;
	const char *signature;
	const char *severity;
};

static const struct pacc_us_suspect pacc_us_suspects[] = {
	{ "gdb",           "gdb",              "medium" },
	{ "lldb",          "lldb",             "medium" },
	{ "strace",        "strace",           "medium" },
	{ "ltrace",        "ltrace",           "medium" },
	{ "frida",         "frida",            "high" },
	{ "cheat-engine",  "cheat_engine",     "high" },
	{ "cheatengine",   "cheat_engine",     "high" },
	{ "scanmem",       "cheat_engine",     "high" },
	{ "gameconqueror", "cheat_engine",     "high" },
	{ "ida64",         "ida",              "medium" },
	{ "ida32",         "ida",              "medium" },
	{ "ghidra",        "ghidra",           "medium" },
	{ "radare2",       "radare2",          "medium" },
	{ "rizin",         "radare2",          "medium" },
	{ "x64dbg",        "x64dbg",           "medium" },
	{ "ollydbg",       "ollydbg",          "medium" },
	{ "ptrace",        "ptrace_tool",      "medium" },
	{ "inject",        "process_injector", "high" },
};

#define PACC_US_SUSPECT_COUNT \
	(sizeof(pacc_us_suspects) / sizeof(pacc_us_suspects[0]))

int pacc_suspect_match_userspace(const char *path, const char **hit_name)
{
	char lower[PACC_PAYLOAD_LEN + 1];
	size_t i, n;

	if (!path)
		return 0;
	/* 先落成 NUL 结尾并转小写：事件里的 payload 由内核侧保证 NUL 结尾
	 * （见 pacc_load_user_str），但这里不信外部数据，自己截断一次。 */
	n = strnlen(path, PACC_PAYLOAD_LEN);
	memcpy(lower, path, n);
	lower[n] = '\0';
	for (i = 0; i < n; i++)
		lower[i] = (char)tolower((unsigned char)lower[i]);

	for (i = 0; i < PACC_US_SUSPECT_COUNT; i++) {
		if (strstr(lower, pacc_us_suspects[i].needle)) {
			if (hit_name)
				*hit_name = pacc_us_suspects[i].signature;
			return 1;
		}
	}
	return 0;
}

static const char *pacc_us_suspect_severity(const char *signature)
{
	size_t i;

	if (!signature)
		return "low";
	for (i = 0; i < PACC_US_SUSPECT_COUNT; i++) {
		if (strcmp(pacc_us_suspects[i].signature, signature) == 0)
			return pacc_us_suspects[i].severity;
	}
	return "low";
}

const char pacc_load_module_note[] =
	"模块名无法在内核侧可靠取得（struct load_info/struct module 的 name 字段形态"
	"随内核版本漂移，硬读会让整个对象加载失败）。若需要模块名，比较事件时间点"
	"前后的 /proc/modules 差异；本事件用于建立「何时有人加载了模块」的时间线。";

/* ============================ 事件名与分级 ============================ */

const char *pacc_event_name(__u32 type)
{
	switch (type) {
	case PACC_EV_EXECVE:
		return "execve";
	case PACC_EV_EXECVE_SUSPICIOUS:
		return "execve_suspicious";
	case PACC_EV_OPENAT:
		return "openat";
	case PACC_EV_OPENAT_SENSITIVE:
		return "openat_sensitive";
	case PACC_EV_PTRACE:
		return "ptrace";
	case PACC_EV_PTRACE_DANGEROUS:
		return "ptrace_dangerous";
	case PACC_EV_PROCESS_VM_WRITEV:
		return "process_vm_writev";
	case PACC_EV_LOAD_MODULE:
		return "load_module";
	case PACC_EV_UPROBE_HOOK:
		return "uprobe_hook";
	default:
		return "unknown";
	}
}

const char *pacc_event_severity(__u32 type)
{
	switch (type) {
	case PACC_EV_EXECVE_SUSPICIOUS:
	case PACC_EV_PTRACE_DANGEROUS:
	case PACC_EV_PROCESS_VM_WRITEV:
		return "high";
	case PACC_EV_OPENAT_SENSITIVE:
	case PACC_EV_LOAD_MODULE:
	case PACC_EV_UPROBE_HOOK:
		return "medium";
	case PACC_EV_EXECVE:
	case PACC_EV_OPENAT:
	case PACC_EV_PTRACE:
		return "low";
	default:
		return "low";
	}
}

/* ============================ NDJSON 构造 ============================
 * jbuf / j_raw / j_str / j_key / j_kv_* / pacc_jbuf_finish 来自 pacc_json.h
 * （与 procfs 回退共用一份实现，避免转义逻辑漂移）。 */

int pacc_event_to_json(const struct pacc_event *e, char *out, unsigned int outsz)
{
	struct pacc_jbuf b;
	struct timespec rt, mt;
	const char *name;
	const char *sev;
	const char *us_hit = NULL;
	const char *sig = NULL;
	unsigned long long epoch_s, epoch_ms, mono_ns = 0, lat_ns = 0;
	char pathbuf[PACC_PAYLOAD_LEN + 1];
	unsigned int plen;

	if (!e || !out || outsz < 32)
		return -1;

	plen = e->payload_len > PACC_PAYLOAD_LEN ? PACC_PAYLOAD_LEN : e->payload_len;
	memcpy(pathbuf, e->payload, plen);
	pathbuf[plen] = '\0';

	name = pacc_event_name(e->type);
	sev = pacc_event_severity(e->type);

	/* 用户态复核：内核侧只匹配 basename 的 11 个前缀，这里用整条路径再判一次，
	 * 命中就升级为 high（例如 /opt/ghidra/support/ghidraRun 这种内核侧匹配不到
	 * 的形态）。只升不降：内核侧已判可疑的不会被这里改回 low。 */
	if ((e->type == PACC_EV_EXECVE || e->type == PACC_EV_EXECVE_SUSPICIOUS) &&
	    plen > 0 && pacc_suspect_match_userspace(pathbuf, &us_hit)) {
		if (e->type == PACC_EV_EXECVE) {
			name = "execve_suspicious";
			sev = "high";
		}
		sig = us_hit;
		if (strcmp(sev, "high") != 0)
			sev = pacc_us_suspect_severity(us_hit);
	}

	clock_gettime(CLOCK_REALTIME, &rt);
	clock_gettime(CLOCK_MONOTONIC, &mt);
	epoch_s = (unsigned long long)rt.tv_sec;
	epoch_ms = epoch_s * 1000ULL + (unsigned long long)(rt.tv_nsec / 1000000);
	mono_ns = (unsigned long long)mt.tv_sec * 1000000000ULL + (unsigned long long)mt.tv_nsec;
	/* 采集到用户态的时延。内核 bpf_ktime_get_ns 与 CLOCK_MONOTONIC 同源
	 * （都是 boot 起的单调时钟），可以直接相减。它是「环缓冲是否在堆积」的
	 * 唯一直接证据，也是 APM 侧关心的一项。 */
	if (mono_ns >= e->ts_ns)
		lat_ns = mono_ns - e->ts_ns;

	memset(&b, 0, sizeof(b));
	b.buf = out;
	b.cap = outsz;
	j_raw(&b, "{", 1);

	j_key(&b, "abi");
	j_fmt(&b, "%u", (unsigned)PACC_EVENT_ABI_VERSION);
	j_kv_str(&b, "event", name);
	j_kv_u64(&b, "event_type", e->type);
	j_kv_u64(&b, "probe", PACC_PROBE_OF(e->flags));
	j_kv_str(&b, "severity", sev);
	j_kv_u64(&b, "pid", e->pid);
	j_kv_u64(&b, "tgid", e->tgid);
	j_kv_u64(&b, "ppid", e->ppid);
	j_kv_u64(&b, "uid", e->uid);
	j_kv_u64(&b, "gid", e->gid);
	j_key(&b, "comm");
	j_str(&b, e->comm, PACC_COMM_LEN);
	j_kv_u64(&b, "ts", epoch_s);
	j_kv_u64(&b, "ts_millis", epoch_ms);
	j_kv_u64(&b, "kts_ns", e->ts_ns);
	j_kv_u64(&b, "lat_ns", lat_ns);

	/* 载荷：按事件类型的形态表解读（见 pacc_ebpf.h） */
	if (e->type == PACC_EV_PROCESS_VM_WRITEV) {
		struct pacc_iovec64 iov;
		unsigned long long addr = 0, wlen = 0;

		if (plen == sizeof(iov)) {
			memcpy(&iov, e->payload, sizeof(iov));
			addr = iov.iov_base;
			wlen = iov.iov_len;
		}
		j_kv_u64(&b, "target_addr", addr);
		j_kv_u64(&b, "write_len", wlen);
	} else if (plen > 0) {
		j_kv_str(&b, "path", pathbuf);
	}

	j_key(&b, "detail");
	j_raw(&b, "{", 1);
	j_key(&b, "arg0");
	j_fmt(&b, "%llu", (unsigned long long)e->arg0);
	j_key(&b, "arg1");
	j_fmt(&b, "%llu", (unsigned long long)e->arg1);
	j_key(&b, "arg2");
	j_fmt(&b, "%llu", (unsigned long long)e->arg2);
	j_kv_u64(&b, "flags_low", PACC_FLAG_OF(e->flags));
	j_kv_u64(&b, "payload_len", plen);
	if (sig)
		j_kv_str(&b, "signature_hit", sig);
	if (e->type == PACC_EV_LOAD_MODULE)
		j_kv_str(&b, "note", pacc_load_module_note);
	j_raw(&b, "}", 1);

	j_raw(&b, "}", 1);
	/* 宁可不发，也不发半截 JSON——半截 JSON 在消费端是「认不出的行」，
	 * 会被静默丢弃，比明确丢一条更难排查。 */
	return pacc_jbuf_finish(&b);
}

/* ============================ 事件出口 ============================ */

static int pacc_mkdir_parent(const char *path)
{
	char tmp[108];
	char *p;

	snprintf(tmp, sizeof(tmp), "%s", path);
	p = strrchr(tmp, '/');
	if (!p || p == tmp)
		return 0;
	*p = '\0';
	/* 逐级创建；已存在（EEXIST）不是错误 */
	for (p = tmp + 1; *p; p++) {
		if (*p != '/')
			continue;
		*p = '\0';
		if (mkdir(tmp, 0755) != 0 && errno != EEXIST)
			return -1;
		*p = '/';
	}
	if (mkdir(tmp, 0755) != 0 && errno != EEXIST)
		return -1;
	return 0;
}

int pacc_sink_init(struct pacc_sink *s, const char *sock_path, int use_stdout)
{
	struct sockaddr_un addr;

	if (!s)
		return -1;
	memset(s, 0, sizeof(*s));
	s->listen_fd = -1;
	s->client_fd = -1;
	snprintf(s->path, sizeof(s->path), "%s", sock_path ? sock_path : PACC_DEFAULT_SOCKET);

	if (use_stdout) {
		snprintf(s->path, sizeof(s->path), "stdout");
		return 0;
	}

	pacc_mkdir_parent(s->path);

	s->listen_fd = socket(AF_UNIX, SOCK_STREAM, 0);
	if (s->listen_fd < 0) {
		fprintf(stderr, "[PACC] socket(AF_UNIX) 失败：%s；改用 stdout\n", strerror(errno));
		snprintf(s->path, sizeof(s->path), "stdout");
		return 0;
	}
	/* 上次退出残留的 socket 文件会让 bind 报 EADDRINUSE；先清掉。
	 * 这里不做「是否有活进程在监听」的判断：同一台机器上跑两个 loader 本身
	 * 就是配置错误，让后者接管反而是最不坏的结果。 */
	unlink(s->path);

	memset(&addr, 0, sizeof(addr));
	addr.sun_family = AF_UNIX;
	snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", s->path);
	if (bind(s->listen_fd, (struct sockaddr *)&addr, sizeof(addr)) != 0 ||
	    listen(s->listen_fd, 4) != 0) {
		fprintf(stderr, "[PACC] bind/listen %s 失败：%s；改用 stdout\n",
			s->path, strerror(errno));
		close(s->listen_fd);
		s->listen_fd = -1;
		snprintf(s->path, sizeof(s->path), "stdout");
		return 0;
	}
	/* 监听 fd 非阻塞：没有客户端时不能把 loader 卡在 accept 上 */
	fcntl(s->listen_fd, F_SETFL, fcntl(s->listen_fd, F_GETFL, 0) | O_NONBLOCK);
	fprintf(stderr, "[PACC] 事件出口：unix:%s\n", s->path);
	return 0;
}

void pacc_sink_accept(struct pacc_sink *s)
{
	int fd;

	if (!s || s->listen_fd < 0 || s->client_fd >= 0)
		return;
	fd = accept(s->listen_fd, NULL, NULL);
	if (fd < 0)
		return;	/* EAGAIN：没有挂起连接，正常路径 */
	/* 客户端非阻塞：消费端卡住时丢事件并计数，绝不让 loader 停在这里。
	 * 阻塞写会让环缓冲堆积→内核侧丢事件，而内核侧的丢事件只在内核里计数，
	 * 用户态看不见——那是「静默丢」，比这里的「可见丢」更糟。 */
	fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
	s->client_fd = fd;
	fprintf(stderr, "[PACC] 消费端已连接\n");
}

void pacc_sink_emit(struct pacc_sink *s, const char *line, unsigned int len)
{
	unsigned int off = 0;

	if (!s || !line || len == 0)
		return;
	if (s->client_fd < 0 && s->listen_fd < 0) {
		if (fwrite(line, 1, len, stdout) != len || fputc('\n', stdout) == EOF ||
		    fflush(stdout) != 0) {
			s->write_errors++;
			return;
		}
		s->lines++;
		return;
	}
	if (s->client_fd < 0)
		return;	/* 有监听但没人连：事件落在内核缓冲里，不在这里排队 */

	while (off < len) {
		ssize_t n = send(s->client_fd, line + off, (size_t)(len - off), MSG_NOSIGNAL);

		if (n > 0) {
			off += (unsigned int)n;
			continue;
		}
		if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) {
			/* 缓冲区满：丢这一条并计数，断开连接让消费端重连后从头
			 * 建立背压关系。断开比继续憋着更能让问题浮出来。 */
			s->write_errors++;
			close(s->client_fd);
			s->client_fd = -1;
			return;
		}
		s->write_errors++;
		close(s->client_fd);
		s->client_fd = -1;
		return;
	}
	if (send(s->client_fd, "\n", 1, MSG_NOSIGNAL) < 0) {
		s->write_errors++;
		close(s->client_fd);
		s->client_fd = -1;
		return;
	}
	s->lines++;
}

void pacc_sink_close(struct pacc_sink *s)
{
	if (!s)
		return;
	if (s->client_fd >= 0)
		close(s->client_fd);
	if (s->listen_fd >= 0) {
		close(s->listen_fd);
		unlink(s->path);
	}
	s->client_fd = -1;
	s->listen_fd = -1;
}

/* ============================ 事件回调 ============================ */

static struct pacc_sink *g_sink;

static void pacc_handle_raw(const void *data, unsigned int size)
{
	static char line[PACC_JSON_MAX];
	const struct pacc_event *e = (const struct pacc_event *)data;
	int n;

	if (!e || size != sizeof(*e)) {
		/* 结构体大小不符：内核对象与用户态头文件版本不一致（典型的
		 * 「只更新了一边」）。直接丢并计数，不要按错误的布局解码。 */
		fprintf(stderr, "[PACC] 丢弃事件：size=%u 期望 %zu"
				"（.bpf.o 与本程序的事件结构不一致，需重新 make bpf）\n",
			size, sizeof(*e));
		return;
	}
	if (e->type < PACC_EV_MAX)
		g_evt_count[e->type]++;
	g_evt_total++;

	n = pacc_event_to_json(e, line, sizeof(line));
	if (n < 0) {
		fprintf(stderr, "[PACC] 丢弃事件：JSON 缓冲不足（payload 过长？）\n");
		return;
	}
	pacc_sink_accept(g_sink);
	pacc_sink_emit(g_sink, line, (unsigned int)n);
}

static int pacc_on_ring_sample(void *ctx, void *data, size_t size)
{
	(void)ctx;
	pacc_handle_raw(data, (unsigned int)size);
	return 0;
}

static void pacc_on_perf_sample(void *ctx, int cpu, void *data, __u32 size)
{
	(void)ctx;
	(void)cpu;
	pacc_handle_raw(data, size);
}

static void pacc_on_perf_lost(void *ctx, int cpu, __u64 cnt)
{
	(void)ctx;
	/* 内核侧丢事件必须大声报：这是「监控有空洞」的唯一外部信号。 */
	fprintf(stderr, "[PACC] 警告：perf buffer 在 cpu%d 上丢失 %llu 条事件\n",
		cpu, (unsigned long long)cnt);
}

/* ============================ 挂载点表 ============================ */

enum pacc_attach_kind {
	PACC_ATTACH_TRACEPOINT,
	PACC_ATTACH_KPROBE,
	PACC_ATTACH_UPROBE,
};

struct pacc_attach_spec {
	const char *obj;		/* 对象基名 */
	const char *prog;		/* 程序名（SEC 之外 libbpf 用的名字就是函数名） */
	enum pacc_attach_kind kind;
	const char *const *symbols;	/* kprobe: 内核符号候选；uprobe: 用户态符号候选 */
	unsigned int nsymbols;
	const char *what;		/* 这个挂载点抓什么（日志/说明用） */
};

/* load_module 的候选符号：设计文档指定 load_module；但该函数是 static、且内核
 * 6.x 重写过模块加载器，不同内核上真正能挂的符号不一样。用运行期改挂载点
 * （bpf_program__attach_kprobe 显式传符号名）按序试，比赌一个名字稳。 */
static const char *pacc_syms_load_module[] = {
	"load_module", "__x64_sys_finit_module", "__x64_sys_init_module",
};
static const char *pacc_syms_dlopen[] = { "dlopen" };
static const char *pacc_syms_dlsym[] = { "dlsym" };

static const struct pacc_attach_spec pacc_attach_specs[] = {
	{ "execve", "pacc_execve", PACC_ATTACH_TRACEPOINT, NULL, 0, "进程创建" },
	{ "openat", "pacc_openat", PACC_ATTACH_TRACEPOINT, NULL, 0, "文件打开" },
	{ "ptrace", "pacc_ptrace", PACC_ATTACH_TRACEPOINT, NULL, 0, "调试/注入" },
	{ "process_vm_writev", "pacc_process_vm_writev", PACC_ATTACH_TRACEPOINT,
	  NULL, 0, "跨进程内存写" },
	{ "load_module", "pacc_load_module", PACC_ATTACH_KPROBE,
	  pacc_syms_load_module, 3, "内核模块加载" },
	{ "uprobe", "pacc_uprobe_dlopen", PACC_ATTACH_UPROBE, pacc_syms_dlopen, 1,
	  "用户态 dlopen（库注入入口）" },
	{ "uprobe", "pacc_uprobe_dlsym", PACC_ATTACH_UPROBE, pacc_syms_dlsym, 1,
	  "用户态 dlsym（符号解析）" },
};

#define PACC_ATTACH_SPEC_COUNT \
	(sizeof(pacc_attach_specs) / sizeof(pacc_attach_specs[0]))

static const char *pacc_object_names[PACC_MAX_OBJECTS] = {
	"execve", "openat", "ptrace", "process_vm_writev", "load_module", "uprobe",
};

/* ============================ 对象加载与挂载 ============================ */

struct pacc_ebpf_set {
	const char *variant;
	const char *dir;
	/* 按 pacc_object_names 的下标索引：NULL 表示该对象尚未加载或已关闭 */
	struct bpf_object *objs[PACC_MAX_OBJECTS];
	struct bpf_link *links[PACC_MAX_LINKS];
	int n_links;
	int evt_fds[PACC_MAX_OBJECTS];
	int n_evt_fds;
	int n_failed;
};

static int pacc_variant_is_ringbuf(const char *variant)
{
	return variant && strncmp(variant, "ringbuf", 7) == 0;
}

/* 变体的对象按需加载：同一变体里同一对象只 open/load 一次。
 * 按需而不是一次全加载，是为了让「某个对象缺失/加载失败」只影响用到它的
 * 挂载点——这就是逐挂载点隔离的落点。 */
static struct bpf_object *pacc_object_get(struct pacc_ebpf_set *set, const char *base)
{
	char path[512];
	struct bpf_object *obj;
	int slot = -1;
	int i;

	for (i = 0; i < PACC_MAX_OBJECTS; i++) {
		if (strcmp(pacc_object_names[i], base) == 0) {
			slot = i;
			break;
		}
	}
	if (slot < 0) {
		pacc_set_lasterr("内部错误：未知对象基名 %s", base);
		return NULL;
	}
	if (set->objs[slot])
		return set->objs[slot];

	snprintf(path, sizeof(path), "%s/%s.%s.bpf.o", set->dir, base, set->variant);
	obj = bpf_object__open_file(path, NULL);
	if (!obj || libbpf_get_error(obj)) {
		long err = obj ? libbpf_get_error(obj) : -errno;

		/* open 失败最常见的原因不是权限，是对象还没编译：先把话说到点上 */
		if (access(path, F_OK) != 0)
			pacc_set_lasterr("未找到 %s（先执行 make bpf；"
					 "或从构建机分发 .bpf.o 后放在 --object-dir）", path);
		else
			pacc_set_lasterr("打开 %s 失败：%s（errno=%ld）",
					 path, strerror((int)(err < 0 ? -err : err)),
					 err < 0 ? -err : err);
		if (obj)
			bpf_object__close(obj);
		return NULL;
	}

	/* perf 变体：map 容量按实际 CPU 数设置。写死值在 CPU 更多的机器上会丢事件 */
	{
		struct bpf_map *m = bpf_object__find_map_by_name(obj, PACC_MAP_PERF_EVENTS);

		if (m) {
			int ncpu = libbpf_num_possible_cpus();

			if (ncpu > 0)
				bpf_map__set_max_entries(m, (unsigned int)ncpu);
		}
	}

	i = bpf_object__load(obj);
	if (i != 0) {
		pacc_set_lasterr("加载 %s 失败：%s（errno=%d）；libbpf 报：%s",
				 path, strerror(-i), -i, g_lasterr);
		bpf_object__close(obj);
		return NULL;
	}

	/* 运行期配置：ARRAY map，加载后可随时改，不用重载程序 */
	{
		struct bpf_map *m = bpf_object__find_map_by_name(obj, PACC_MAP_CONFIG);

		if (m) {
			struct pacc_config cfg;
			__u32 key = 0;

			memset(&cfg, 0, sizeof(cfg));
			cfg.capture_all_openat = g_capture_all_openat ? 1u : 0u;
			cfg.capture_all_execve = g_capture_all_execve ? 1u : 0u;
			bpf_map_update_elem(bpf_map__fd(m), &key, &cfg, BPF_ANY);
		}
	}

	set->objs[slot] = obj;

	/* 记下该对象的事件 map fd（ringbuf 与 perf 二选一，按名字找） */
	{
		struct bpf_map *m = bpf_object__find_map_by_name(obj, PACC_MAP_RINGBUF);

		if (!m)
			m = bpf_object__find_map_by_name(obj, PACC_MAP_PERF_EVENTS);
		if (m && set->n_evt_fds < PACC_MAX_OBJECTS)
			set->evt_fds[set->n_evt_fds++] = bpf_map__fd(m);
	}
	return obj;
}

/* 挂载一个挂载点，把成功的 link 追加进 set->links，返回新增的 link 数。
 * 返回 0 即失败（原因见 g_lasterr 与 libbpf 日志）。
 * 返回计数而不是单个 link：uprobe 需要对每个受保护进程各挂一份，天然是多个。 */
static int pacc_attach_one(struct pacc_ebpf_set *set, const struct pacc_attach_spec *sp,
			   struct bpf_program *prog, const char *uprobe_lib)
{
	struct bpf_link *link = NULL;
	int added = 0;
	unsigned int i;

	switch (sp->kind) {
	case PACC_ATTACH_TRACEPOINT:
		/* SEC("tracepoint/<cat>/<name>") 已写明挂载点，auto-attach 足够；
		 * tracepoint 是稳定 ABI，不需要候选符号那套兜底。 */
		link = bpf_program__attach(prog);
		if (!link || libbpf_get_error(link)) {
			pacc_set_lasterr("tracepoint 挂载失败（内核未启用该 tracepoint？）：%s",
					 g_lasterr);
			return 0;
		}
		if (set->n_links < PACC_MAX_LINKS)
			set->links[set->n_links++] = link;
		return 1;

	case PACC_ATTACH_KPROBE:
		for (i = 0; i < sp->nsymbols && added == 0; i++) {
			link = bpf_program__attach_kprobe(prog, false, sp->symbols[i]);
			if (!link || libbpf_get_error(link)) {
				if (g_verbose)
					fprintf(stderr, "[PACC]   kprobe/%s 不可用，试下一个候选\n",
						sp->symbols[i]);
				link = NULL;
				continue;
			}
			fprintf(stderr, "[PACC]   kprobe/%s 挂载成功\n", sp->symbols[i]);
			if (set->n_links < PACC_MAX_LINKS)
				set->links[set->n_links++] = link;
			added = 1;
		}
		if (added == 0)
			pacc_set_lasterr("候选内核符号全部挂不上（%u 个候选，"
					 "常见原因：符号被内联/改名，或 kprobe 被内核配置禁用）；"
					 "libbpf 报：%s", sp->nsymbols, g_lasterr);
		return added;

	case PACC_ATTACH_UPROBE:
		/* uprobe 必须逐个受保护进程挂。全局挂会把整机所有进程的 dlopen
		 * 都打进来，噪声淹没信号，而无关进程的 dlopen 没有情报价值。 */
		if (g_npids == 0) {
			pacc_set_lasterr("未指定受保护进程（--pid），跳过 uprobe："
					 "uprobe 是 per-PID 挂载，没有目标就没有挂载点");
			return 0;
		}
		for (i = 0; i < sp->nsymbols; i++) {
			int p;

			for (p = 0; p < g_npids; p++) {
				struct bpf_uprobe_opts opts;

				memset(&opts, 0, sizeof(opts));
				opts.sz = sizeof(opts);
				opts.func_name = sp->symbols[i];
				opts.retprobe = false;
				link = bpf_program__attach_uprobe_opts(prog, g_pids[p],
								       uprobe_lib, 0, &opts);
				if (!link || libbpf_get_error(link)) {
					if (g_verbose)
						fprintf(stderr, "[PACC]   uprobe %s:%s pid=%d "
								"失败，跳过\n", uprobe_lib,
							sp->symbols[i], (int)g_pids[p]);
					continue;
				}
				fprintf(stderr, "[PACC]   uprobe %s:%s (pid=%d) 挂载成功\n",
					uprobe_lib, sp->symbols[i], (int)g_pids[p]);
				if (set->n_links < PACC_MAX_LINKS)
					set->links[set->n_links++] = link;
				added++;
			}
		}
		if (added == 0)
			pacc_set_lasterr("uprobe %s:%s 在 %d 个受保护进程上都挂不上"
					 "（二进制路径不对？进程已退出？权限不足？）；libbpf 报：%s",
					 uprobe_lib, sp->symbols[0], g_npids, g_lasterr);
		return added;
	}
	return 0;
}

/* 尝试一个变体：返回挂上的 link 数量。0 表示这个变体整体不可用。 */
static int pacc_load_variant(struct pacc_ebpf_set *set, const char *dir,
			     const char *variant, const char *uprobe_lib)
{
	unsigned int i;

	memset(set, 0, sizeof(*set));
	set->variant = variant;
	set->dir = dir;

	for (i = 0; i < PACC_ATTACH_SPEC_COUNT; i++) {
		const struct pacc_attach_spec *sp = &pacc_attach_specs[i];
		struct bpf_object *obj = pacc_object_get(set, sp->obj);
		struct bpf_program *prog;

		if (!obj) {
			set->n_failed++;
			fprintf(stderr, "[PACC] 挂载点「%s」不可用：%s\n", sp->what, g_lasterr);
			continue;
		}
		prog = bpf_object__find_program_by_name(obj, sp->prog);
		if (!prog) {
			set->n_failed++;
			fprintf(stderr, "[PACC] 挂载点「%s」不可用：对象 %s.%s.bpf.o 里"
					"找不到程序 %s\n",
				sp->what, sp->obj, variant, sp->prog);
			continue;
		}
		if (pacc_attach_one(set, sp, prog, uprobe_lib) == 0) {
			set->n_failed++;
			fprintf(stderr, "[PACC] 挂载点「%s」attach 失败：%s\n",
				sp->what, g_lasterr[0] ? g_lasterr : "见上方 libbpf 日志");
			continue;
		}
		fprintf(stderr, "[PACC] 挂载点「%s」就绪（%s/%s）\n", sp->what, variant, sp->prog);
	}
	return set->n_links;
}

static void pacc_free_set(struct pacc_ebpf_set *set)
{
	int i;

	for (i = 0; i < set->n_links; i++) {
		if (set->links[i])
			bpf_link__destroy(set->links[i]);
	}
	for (i = 0; i < PACC_MAX_OBJECTS; i++) {
		if (set->objs[i])
			bpf_object__close(set->objs[i]);
	}
	memset(set, 0, sizeof(*set));
}

/* ============================ 统计与状态事件 ============================ */

static void pacc_emit_status(struct pacc_sink *sink, const struct pacc_capability *cap,
			     const char *mode_note)
{
	char line[PACC_JSON_MAX];
	struct pacc_jbuf b;
	int n;

	if (!sink)
		return;
	/* 走 jbuf 而不是 snprintf：reason / attach_note 里可能出现引号（libbpf 与
	 * strerror 的文本不可控），手拼 JSON 迟早会因为一个引号把整行毁掉。 */
	memset(&b, 0, sizeof(b));
	b.buf = line;
	b.cap = sizeof(line);
	j_raw(&b, "{", 1);
	j_kv_str(&b, "event", "probe_status");
	j_kv_str(&b, "mode", pacc_mode_name(cap->mode));
	j_key(&b, "kernel");
	j_fmt(&b, "\"%u.%u.%u\"", cap->kver_major, cap->kver_minor, cap->kver_patch);
	j_kv_u64(&b, "btf", cap->have_btf ? 1 : 0);
	j_kv_u64(&b, "bpf",
		 cap->have_bpf_syscall < 0 ? 0 : (unsigned long long)cap->have_bpf_syscall);
	j_kv_u64(&b, "bpf_errno", (unsigned long long)cap->bpf_errno);
	j_kv_u64(&b, "ringbuf", cap->have_ringbuf ? 1 : 0);
	j_kv_u64(&b, "root", cap->is_root ? 1 : 0);
	j_kv_u64(&b, "ldm_module", cap->ldm_module_loaded ? 1 : 0);
	j_kv_str(&b, "attach_note", mode_note ? mode_note : "");
	j_kv_str(&b, "reason", cap->reason);
	j_kv_u64(&b, "ts", (unsigned long long)time(NULL));
	j_raw(&b, "}", 1);
	n = pacc_jbuf_finish(&b);
	if (n < 0) {
		fprintf(stderr, "[PACC] probe_status 过长，未能发出\n");
		return;
	}
	pacc_sink_accept(sink);
	pacc_sink_emit(sink, line, (unsigned int)n);
}

static void pacc_stats_tick(struct pacc_sink *sink, int force)
{
	time_t now = time(NULL);

	if (!force && now - g_last_stats < PACC_STATS_SEC)
		return;
	g_last_stats = now;
	fprintf(stderr, "[PACC] 事件统计：total=%llu execve=%llu execve_susp=%llu "
			"openat_sens=%llu ptrace=%llu ptrace_dang=%llu vm_writev=%llu "
			"load_module=%llu uprobe=%llu | 写出=%llu 写失败=%llu\n",
		g_evt_total,
		g_evt_count[PACC_EV_EXECVE], g_evt_count[PACC_EV_EXECVE_SUSPICIOUS],
		g_evt_count[PACC_EV_OPENAT_SENSITIVE], g_evt_count[PACC_EV_PTRACE],
		g_evt_count[PACC_EV_PTRACE_DANGEROUS],
		g_evt_count[PACC_EV_PROCESS_VM_WRITEV],
		g_evt_count[PACC_EV_LOAD_MODULE], g_evt_count[PACC_EV_UPROBE_HOOK],
		sink ? sink->lines : 0, sink ? sink->write_errors : 0);
}

/* ============================ 事件循环 ============================ */

static int pacc_run_ringbuf(struct pacc_ebpf_set *set, struct pacc_sink *sink)
{
	struct ring_buffer *rb;
	int i, err;

	rb = ring_buffer__new(set->evt_fds[0], pacc_on_ring_sample, NULL, NULL);
	if (!rb || libbpf_get_error(rb)) {
		fprintf(stderr, "[PACC] ring_buffer__new 失败：%s\n",
			strerror(errno));
		return -1;
	}
	/* 六个挂载点各有自己的事件 map（对象隔离的代价）。ring_buffer 支持把多个
	 * map 加进同一个管理器，一次 poll 全覆盖。 */
	for (i = 1; i < set->n_evt_fds; i++) {
		err = ring_buffer__add(rb, set->evt_fds[i], pacc_on_ring_sample, NULL);
		if (err) {
			fprintf(stderr, "[PACC] ring_buffer__add(map%d) 失败：%d"
					"（该挂载点的事件将收不到）\n", i, err);
		}
	}

	while (!g_stop) {
		err = ring_buffer__poll(rb, PACC_POLL_MS);
		if (err == -EINTR)
			continue;
		if (err < 0)
			fprintf(stderr, "[PACC] ring_buffer__poll 错误：%s（继续）\n",
				strerror(-err));
		if (g_reload) {
			g_reload = 0;
			fprintf(stderr, "[PACC] 收到 SIGHUP：受保护进程集合变更需重启"
					"loader 才生效（uprobe 是 per-PID 挂载）\n");
		}
		pacc_stats_tick(sink, 0);
	}
	ring_buffer__free(rb);
	return 0;
}

static int pacc_run_perf(struct pacc_ebpf_set *set, struct pacc_sink *sink)
{
	struct perf_buffer *pb[PACC_MAX_OBJECTS];
	int npb = 0, i;

	for (i = 0; i < set->n_evt_fds; i++) {
		pb[npb] = perf_buffer__new(set->evt_fds[i], PACC_PERF_PAGES,
					   pacc_on_perf_sample, pacc_on_perf_lost,
					   NULL, NULL);
		if (!pb[npb] || libbpf_get_error(pb[npb])) {
			fprintf(stderr, "[PACC] perf_buffer__new(map%d) 失败：%s"
					"（该挂载点的事件将收不到）\n",
				i, strerror(errno));
			continue;
		}
		npb++;
	}
	if (npb == 0) {
		fprintf(stderr, "[PACC] 没有任何 perf buffer 建立成功，无法接收事件\n");
		return -1;
	}

	/* perf 没有「一个管理器管多个 map」的接口（ring_buffer__add 有，
	 * perf_buffer 没有），只能逐个 poll。20ms 一个 → 最坏 120ms 端到端时延，
	 * CPU 开销接近 0。这个取舍写在注释里，免得后来人以为是漏了合并。 */
	while (!g_stop) {
		for (i = 0; i < npb; i++)
			perf_buffer__poll(pb[i], PACC_PERF_POLL_MS);
		if (g_reload) {
			g_reload = 0;
			fprintf(stderr, "[PACC] 收到 SIGHUP：受保护进程集合变更需重启"
					"loader 才生效（uprobe 是 per-PID 挂载）\n");
		}
		pacc_stats_tick(sink, 0);
	}
	for (i = 0; i < npb; i++)
		perf_buffer__free(pb[i]);
	return 0;
}

static int pacc_run_ebpf(struct pacc_ebpf_set *set, struct pacc_sink *sink)
{
	if (set->n_evt_fds == 0) {
		fprintf(stderr, "[PACC] 没有可读的事件 map，放弃 eBPF 路径\n");
		return -1;
	}
	if (pacc_variant_is_ringbuf(set->variant))
		return pacc_run_ringbuf(set, sink);
	return pacc_run_perf(set, sink);
}

/* ============================ procfs 回退循环 ============================ */

static int pacc_run_procfs(struct pacc_sink *sink, int interval)
{
	struct pacc_procfs_stats stats;

	if (!pacc_procfs_available()) {
		fprintf(stderr, "[PACC] 致命：%s\n", PACC_PROC_UNSUPPORTED);
		return -1;
	}
	fprintf(stderr, "[PACC] 进入 procfs 回退模式：事件级监控缺失，只有轮询快照"
			"（每 %ds 一轮）。事件级能力需内核 >=5.4 且 bpf() 可用。\n",
		interval);

	while (!g_stop) {
		int i;
		int n = 0;

		for (i = 0; i < interval * 10 && !g_stop; i++)
			usleep(100 * 1000);

		memset(&stats, 0, sizeof(stats));
		n = pacc_procfs_scan_once(sink, 0, &stats);
		if (n < 0) {
			fprintf(stderr, "[PACC] /proc 扫描失败：%s\n", strerror(errno));
			continue;
		}
		fprintf(stderr, "[PACC] procfs 扫描：checked=%lu skipped_kernel=%lu "
				"findings=%lu\n",
			stats.scanned, stats.kernel_threads, stats.findings);
	}
	return 0;
}

/* ============================ 入口 ============================ */

static void pacc_on_signal(int sig)
{
	if (sig == SIGHUP)
		g_reload = 1;
	else
		g_stop = 1;
}

static void pacc_usage(const char *argv0)
{
	fprintf(stderr,
		"用法：%s [选项]\n"
		"  --object-dir DIR    .bpf.o 所在目录（默认 ./build）\n"
		"  --socket PATH       事件输出到的 UNIX socket（默认 %s）\n"
		"  --stdout            事件写 stdout 而不是 socket\n"
		"  --pid PID           受保护进程，uprobe 的挂载目标；可重复\n"
		"  --uprobe-lib PATH   uprobe 目标二进制（默认 libc.so.6）\n"
		"  --uprobe-sym-dlopen SYM / --uprobe-sym-dlsym SYM  覆盖默认符号名\n"
		"  --capture-all-openat   上报所有 openat（默认只报敏感路径）\n"
		"  --no-capture-execve    关闭 execve 全量上报\n"
		"  --force-procfs         强制走 procfs 回退\n"
		"  --variant NAME         强制对象变体（ringbuf|perf|perf-compat54）\n"
		"  --interval SEC         procfs 回退的扫描间隔（默认 %d）\n"
		"  -v, --verbose          打印 libbpf 调试日志\n"
		"  -h, --help             本帮助\n",
		argv0, PACC_DEFAULT_SOCKET, PACC_PROC_INTERVAL);
}

int main(int argc, char **argv)
{
	struct pacc_capability cap;
	struct pacc_sink sink;
	struct pacc_ebpf_set set;
	const char *object_dir = "./build";
	const char *socket_path = PACC_DEFAULT_SOCKET;
	const char *uprobe_lib = "libc.so.6";
	const char *force_variant = NULL;
	const char *variants[4];
	int use_stdout = 0, force_procfs = 0, interval = PACC_PROC_INTERVAL;
	int nvariants = 0, i, attached = 0, isel = 0;
	char desc[PACC_REASON_MAX + 160];
	char note[PACC_REASON_MAX + 128];

	for (i = 1; i < argc; i++) {
		const char *a = argv[i];
		const char *v = (i + 1 < argc) ? argv[i + 1] : NULL;

		if (!strcmp(a, "-h") || !strcmp(a, "--help")) {
			pacc_usage(argv[0]);
			return 0;
		} else if (!strcmp(a, "-v") || !strcmp(a, "--verbose")) {
			g_verbose = 1;
		} else if (!strcmp(a, "--stdout")) {
			use_stdout = 1;
		} else if (!strcmp(a, "--force-procfs")) {
			force_procfs = 1;
		} else if (!strcmp(a, "--capture-all-openat")) {
			g_capture_all_openat = 1;
		} else if (!strcmp(a, "--no-capture-execve")) {
			g_capture_all_execve = 0;
		} else if (!strcmp(a, "--object-dir") && v) {
			object_dir = v;
			i++;
		} else if (!strcmp(a, "--socket") && v) {
			socket_path = v;
			i++;
		} else if (!strcmp(a, "--interval") && v) {
			interval = atoi(v);
			if (interval < 1)
				interval = 1;
			i++;
		} else if (!strcmp(a, "--uprobe-lib") && v) {
			uprobe_lib = v;
			i++;
		} else if (!strcmp(a, "--uprobe-sym-dlopen") && v) {
			pacc_syms_dlopen[0] = v;
			i++;
		} else if (!strcmp(a, "--uprobe-sym-dlsym") && v) {
			pacc_syms_dlsym[0] = v;
			i++;
		} else if (!strcmp(a, "--variant") && v) {
			force_variant = v;
			i++;
		} else if (!strcmp(a, "--pid") && v) {
			if (g_npids >= PACC_MAX_PIDS) {
				fprintf(stderr, "[PACC] --pid 超过上限 %d，多余的忽略\n",
					PACC_MAX_PIDS);
				continue;
			}
			g_pids[g_npids++] = (pid_t)atoi(v);
			i++;
		} else {
			fprintf(stderr, "[PACC] 未知参数：%s\n", a);
			pacc_usage(argv[0]);
			return 2;
		}
	}

	signal(SIGINT, pacc_on_signal);
	signal(SIGTERM, pacc_on_signal);
	signal(SIGHUP, pacc_on_signal);
	signal(SIGPIPE, SIG_IGN);	/* 消费端断开不能把 loader 打死 */
	libbpf_set_print(pacc_libbpf_print);
	g_last_stats = time(NULL);

	fprintf(stderr, "[PACC] pacc-ebpf-loader 启动，libbpf %d.%d\n",
		LIBBPF_MAJOR_VERSION, LIBBPF_MINOR_VERSION);

	pacc_probe_capability(&cap);
	if (pacc_capability_describe(&cap, desc, sizeof(desc)) > 0)
		fputs(desc, stderr);

	if (pacc_sink_init(&sink, socket_path, use_stdout) != 0) {
		fprintf(stderr, "[PACC] 事件出口初始化失败\n");
		return 1;
	}
	g_sink = &sink;

	/* ---------- 走 procfs 回退 ---------- */
	if (force_procfs ||
	    cap.mode == PACC_MODE_PROCFS || cap.mode == PACC_MODE_UNAVAILABLE) {
		int rc;

		if (force_procfs)
			snprintf(note, sizeof(note), "被 --force-procfs 强制指定");
		else
			snprintf(note, sizeof(note), "能力探测判定 eBPF 不可用");
		fprintf(stderr, "[PACC] 采集路径选择：procfs 回退（%s）；%s\n",
			note, cap.reason);
		if (cap.ldm_module_loaded)
			fprintf(stderr, "[PACC] pacc_ldm 内核模块已加载"
					"（/sys/kernel/debug/pacc 可见），"
					"其 ptrace/process_vm_readv 命中统计可由 linux-daemon 汇集\n");
		else
			fprintf(stderr, "[PACC] pacc_ldm 内核模块未加载："
					"如需内核侧的 ptrace 命中统计，"
					"请 insmod pacc_ldm.ko（见 README）\n");
		pacc_emit_status(&sink, &cap, note);
		rc = pacc_run_procfs(&sink, interval);
		pacc_sink_close(&sink);
		return rc == 0 ? 0 : 1;
	}

	/* ---------- 选变体 ---------- */
	if (force_variant) {
		variants[nvariants++] = force_variant;
	} else if (cap.mode == PACC_MODE_EBPF_RINGBUF) {
		variants[nvariants++] = "ringbuf";
		variants[nvariants++] = "perf";
	} else {
		/* 5.4 缺 bpf_probe_read_user_str，优先试 compat54 变体；
		 * 5.5~5.7 直接用严格版。都带上对方作兜底，失败重试的代价只有几十毫秒。 */
		if (!cap.have_probe_read_user) {
			variants[nvariants++] = "perf-compat54";
			variants[nvariants++] = "perf";
		} else {
			variants[nvariants++] = "perf";
			variants[nvariants++] = "ringbuf";
		}
	}

	for (isel = 0; isel < nvariants && attached == 0; isel++) {
		int failed_here;

		fprintf(stderr, "[PACC] 尝试对象变体：%s\n", variants[isel]);
		attached = pacc_load_variant(&set, object_dir, variants[isel], uprobe_lib);
		if (attached > 0)
			break;
		/* 先把失败计数抄出来：下面 pacc_free_set 会 memset 整个 set，
		 * 之后再读 set.n_failed 就永远是 0（日志会说「0 个失败」，误导排查）。 */
		failed_here = set.n_failed;
		fprintf(stderr, "[PACC] 变体 %s 一个挂载点也没挂上（%d 个失败）；"
				"尝试下一个可用变体\n", variants[isel], failed_here);
		pacc_free_set(&set);
	}

	if (attached == 0) {
		/* eBPF 全线失败：降级到 procfs，并把失败原因留在日志里。
		 * 这里绝不 exit(1) 了事——反作弊客户端在用户机上退出等于放弃检测，
		 * 而 procfs 回退还能给出进程现状快照。 */
		int rc;

		snprintf(note, sizeof(note),
			 "eBPF 挂载点全部失败（%s）：%s",
			 nvariants ? variants[0] : "无变体", g_lasterr);
		fprintf(stderr, "[PACC] %s\n", note);
		fprintf(stderr, "[PACC] 降级为 procfs 回退模式\n");
		cap.mode = PACC_MODE_PROCFS;
		snprintf(cap.reason, sizeof(cap.reason), "%s", note);
		pacc_emit_status(&sink, &cap, note);
		rc = pacc_run_procfs(&sink, interval);
		pacc_sink_close(&sink);
		return rc == 0 ? 0 : 1;
	}

	snprintf(note, sizeof(note), "对象变体 %s，挂载点 %d/%zu 就绪",
		 set.variant, attached, PACC_ATTACH_SPEC_COUNT);
	if (set.n_failed)
		fprintf(stderr, "[PACC] 注意：%d 个挂载点未就绪，本次采集在这些维度上是"
				"盲的（见上方逐条日志）\n", set.n_failed);
	pacc_emit_status(&sink, &cap, note);

	{
		int rc = pacc_run_ebpf(&set, &sink);

		pacc_stats_tick(&sink, 1);	/* 退出前把累计数打出来，便于验证 A10/A11 */
		pacc_free_set(&set);
		pacc_sink_close(&sink);
		fprintf(stderr, "[PACC] loader 退出\n");
		return rc == 0 ? 0 : 1;
	}
}