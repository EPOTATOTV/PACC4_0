/* ⚠️ 语法检查用桩文件，不是 libbpf 的 bpf/libbpf.h ⚠️
 * 只声明 userspace 下的 .c 实际调用的那批 API，签名与 libbpf 1.x 保持一致——
 * 签名写错的桩会给出虚假的「检查通过」，所以这里刻意照抄真实原型。
 * 真实构建用系统的 libbpf（pkg-config --cflags libbpf）。 */
#ifndef PACC_STUB_LIBBPF_H
#define PACC_STUB_LIBBPF_H

#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>

#include <linux/bpf.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---- 版本与错误处理 ---- */
enum libbpf_print_level {
	LIBBPF_WARN = 0,
	LIBBPF_INFO = 1,
	LIBBPF_DEBUG = 2,
};

typedef int (*libbpf_print_fn_t)(enum libbpf_print_level level, const char *format,
				 va_list args);

libbpf_print_fn_t libbpf_set_print(libbpf_print_fn_t fn);
long libbpf_get_error(const void *ptr);
int libbpf_num_possible_cpus(void);

/* ---- 对象 / 程序 / map / link ---- */
struct bpf_object;
struct bpf_program;
struct bpf_map;
struct bpf_link;

struct bpf_object_open_opts;
struct ring_buffer_opts;
struct perf_buffer_opts;

struct bpf_object *bpf_object__open_file(const char *path,
					 const struct bpf_object_open_opts *opts);
int bpf_object__load(struct bpf_object *obj);
void bpf_object__close(struct bpf_object *obj);
struct bpf_program *bpf_object__find_program_by_name(const struct bpf_object *obj,
						     const char *name);
struct bpf_map *bpf_object__find_map_by_name(const struct bpf_object *obj,
					     const char *name);
int bpf_map__fd(const struct bpf_map *map);
int bpf_map__set_max_entries(struct bpf_map *map, __u32 max_entries);

/* ---- attach ---- */
struct bpf_link *bpf_program__attach(const struct bpf_program *prog);
struct bpf_link *bpf_program__attach_kprobe(const struct bpf_program *prog,
					    bool retprobe, const char *func_name);

struct bpf_uprobe_opts {
	size_t sz;
	size_t ref_ctr_offset;
	__u64 bpf_cookie;
	bool retprobe;
	const char *func_name;
};

struct bpf_link *bpf_program__attach_uprobe_opts(const struct bpf_program *prog,
						 pid_t pid, const char *binary_path,
						 size_t func_offset,
						 const struct bpf_uprobe_opts *opts);
void bpf_link__destroy(struct bpf_link *link);

/* ---- ring buffer（5.8+） ---- */
struct ring_buffer;

typedef int (*ring_buffer_sample_fn)(void *ctx, void *data, size_t size);

struct ring_buffer *ring_buffer__new(int map_fd, ring_buffer_sample_fn sample_cb,
				     void *ctx, const struct ring_buffer_opts *opts);
int ring_buffer__add(struct ring_buffer *rb, int map_fd,
		     ring_buffer_sample_fn sample_cb, void *ctx);
int ring_buffer__poll(struct ring_buffer *rb, int timeout_ms);
void ring_buffer__free(struct ring_buffer *rb);

/* ---- perf event array（5.4+） ---- */
struct perf_buffer;

typedef void (*perf_buffer_sample_fn)(void *ctx, int cpu, void *data, __u32 size);
typedef void (*perf_buffer_lost_fn)(void *ctx, int cpu, __u64 cnt);

struct perf_buffer *perf_buffer__new(int map_fd, size_t page_cnt,
				     perf_buffer_sample_fn sample_cb,
				     perf_buffer_lost_fn lost_cb, void *ctx,
				     const struct perf_buffer_opts *opts);
int perf_buffer__poll(struct perf_buffer *pb, int timeout_ms);
void perf_buffer__free(struct perf_buffer *pb);

#ifdef __cplusplus
}
#endif

#endif /* PACC_STUB_LIBBPF_H */