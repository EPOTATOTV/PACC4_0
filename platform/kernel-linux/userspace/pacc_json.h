/* PACC loader 内部：NDJSON 拼装助手（header-only）
 *
 * 为什么单独抽出来：事件出口有两处生产者——eBPF 事件解码（pacc_ebpf_loader.c）
 * 与 procfs 回退发现（pacc_procfs_fallback.c）。JSON 转义写两遍必然漂移，而
 * 「一个进程名里带引号就把整行 JSON 毁掉」这种 bug 在反作弊里是「静默丢事件」，
 * 最难查。所以实现只留一份。
 *
 * 拼装策略：定长缓冲 + 静默截断 + 末尾统一判超。所有函数在容量不足时只停下
 * 来的事，不返回错误；调用方用 pacc_jbuf_finish() 一次性得知「是否被截断」，
 * 被截断就整条不发——半截 JSON 在消费端等于垃圾。
 */
#ifndef PACC_JSON_H
#define PACC_JSON_H

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

struct pacc_jbuf {
	char *buf;
	unsigned int cap;
	unsigned int len;
	unsigned int truncated;
};

static inline void j_raw(struct pacc_jbuf *b, const char *s, unsigned int n)
{
	unsigned int room = b->cap > b->len ? b->cap - b->len : 0;

	if (n > room) {
		n = room;
		b->truncated = 1;
	}
	if (n == 0)
		return;
	memcpy(b->buf + b->len, s, n);
	b->len += n;
}

static inline void j_fmt(struct pacc_jbuf *b, const char *fmt, ...)
	__attribute__((format(printf, 2, 3)));

static inline void j_fmt(struct pacc_jbuf *b, const char *fmt, ...)
{
	char tmp[320];
	va_list ap;
	int n;

	va_start(ap, fmt);
	n = vsnprintf(tmp, sizeof(tmp), fmt, ap);
	va_end(ap);
	if (n > 0)
		j_raw(b, tmp, (unsigned int)n);
}

/* JSON 字符串（带转义）。控制字符按 \u00XX 转义：进程名里出现 \x01 这类字节
 * 要么是攻击要么是坏数据，两种都不该把 JSON 结构撑破。 */
static inline void j_str(struct pacc_jbuf *b, const char *s, unsigned int n)
{
	char esc[8];
	unsigned int i;

	j_raw(b, "\"", 1);
	for (i = 0; s && i < n; i++) {
		unsigned char c = (unsigned char)s[i];

		if (c == '\0')
			break;
		switch (c) {
		case '"':
			j_raw(b, "\\\"", 2);
			break;
		case '\\':
			j_raw(b, "\\\\", 2);
			break;
		case '\n':
			j_raw(b, "\\n", 2);
			break;
		case '\r':
			j_raw(b, "\\r", 2);
			break;
		case '\t':
			j_raw(b, "\\t", 2);
			break;
		default:
			if (c < 0x20) {
				snprintf(esc, sizeof(esc), "\\u%04x", c);
				j_raw(b, esc, 6);
			} else {
				j_raw(b, (const char *)&s[i], 1);
			}
			break;
		}
	}
	j_raw(b, "\"", 1);
}

static inline void j_cstr(struct pacc_jbuf *b, const char *s)
{
	j_str(b, s, s ? (unsigned int)strlen(s) : 0);
}

/* 写键名并补逗号分隔（第一个键不补：靠 len > 1 判断，即已有 "{"） */
static inline void j_key(struct pacc_jbuf *b, const char *key)
{
	if (b->len > 1)
		j_raw(b, ",", 1);
	j_raw(b, "\"", 1);
	j_raw(b, key, (unsigned int)strlen(key));
	j_raw(b, "\":", 2);
}

static inline void j_kv_str(struct pacc_jbuf *b, const char *key, const char *val)
{
	j_key(b, key);
	j_cstr(b, val);
}

static inline void j_kv_u64(struct pacc_jbuf *b, const char *key, unsigned long long val)
{
	j_key(b, key);
	j_fmt(b, "%llu", val);
}

/* 收尾：写 NUL 并返回内容长度；被截断或放不下结尾 NUL 时返回 -1。 */
static inline int pacc_jbuf_finish(struct pacc_jbuf *b)
{
	if (b->truncated || b->len + 1 > b->cap)
		return -1;
	b->buf[b->len] = '\0';
	return (int)b->len;
}

#endif /* PACC_JSON_H */