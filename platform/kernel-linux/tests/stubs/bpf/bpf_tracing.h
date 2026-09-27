/* ⚠️ 语法检查用桩文件，不是 libbpf 的真 bpf_tracing.h ⚠️
 * 只提供 PT_REGS_PARM*_CORE / PT_REGS_RC_CORE（kprobe 与 uprobe 用的那几个）。
 * 真实构建用系统的 libbpf 头，其寄存器映射按 __TARGET_ARCH_* 自动选择。 */
#ifndef PACC_STUB_BPF_TRACING_H
#define PACC_STUB_BPF_TRACING_H

#if defined(__TARGET_ARCH_x86)
#define __PT_PARM1_REG di
#define __PT_PARM2_REG si
#define __PT_PARM3_REG dx
#define __PT_PARM4_REG cx
#define __PT_PARM5_REG r8
#define __PT_RET_REG sp
#define __PT_FP_REG bp
#else
#error "本桩文件只覆盖 x86_64（真实 bpf_tracing.h 支持多架构，构建时用真实的）"
#endif

#define __PT_REGS_CAST(x) ((const struct pt_regs *)(x))

#define PT_REGS_PARM1(x) (__PT_REGS_CAST(x)->__PT_PARM1_REG)
#define PT_REGS_PARM2(x) (__PT_REGS_CAST(x)->__PT_PARM2_REG)
#define PT_REGS_PARM3(x) (__PT_REGS_CAST(x)->__PT_PARM3_REG)
#define PT_REGS_PARM4(x) (__PT_REGS_CAST(x)->__PT_PARM4_REG)
#define PT_REGS_PARM5(x) (__PT_REGS_CAST(x)->__PT_PARM5_REG)
#define PT_REGS_RC(x) (__PT_REGS_CAST(x)->ax)

/* 真实实现在 _CORE 变体里包一层 BPF_CORE_READ（做字段重定位）；桩里直接读，
 * 因为桩 vmlinux.h 的 pt_regs 字段名就是目标字段名。 */
#define PT_REGS_PARM1_CORE(x) PT_REGS_PARM1(x)
#define PT_REGS_PARM2_CORE(x) PT_REGS_PARM2(x)
#define PT_REGS_PARM3_CORE(x) PT_REGS_PARM3(x)
#define PT_REGS_PARM4_CORE(x) PT_REGS_PARM4(x)
#define PT_REGS_PARM5_CORE(x) PT_REGS_PARM5(x)
#define PT_REGS_RC_CORE(x) PT_REGS_RC(x)

#endif /* PACC_STUB_BPF_TRACING_H */