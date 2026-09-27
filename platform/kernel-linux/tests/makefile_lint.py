#!/usr/bin/env python3
"""Makefile 结构 lint（在无 make 的机器上代替 `make -n`）

只做能机械判定的部分，不假装等价于 make 解析：
  1. ifneq/ifeq/else/endif 与 define/endef 配对
  2. 每行 $(...) 圆括号配平（真正的嵌套扫描，不是数括号个数）
  3. recipe 缩进：规则上下文里出现「空格缩进」的行即报错（make 报 missing separator）
  4. $(eval $(call PACC_BPF_RULE,...)) 展开：三个变体都要有规则，且目标模式参数化

用法： python3 tests/_mk_lint.py Makefile
"""
import re
import sys

path = sys.argv[1]
lines = open(path, encoding="utf-8").read().split("\n")

errors = []
conds = 0
defines = 0
in_rule = False

rule_re = re.compile(r"^[A-Za-z0-9_./$()%-]+\s*:(?!=)")
targets = []


def paren_balance(line):
    """返回 $() 嵌套扫描后的剩余深度（0 = 平衡）"""
    depth = 0
    i = 0
    while i < len(line):
        if line[i] == "$" and i + 1 < len(line) and line[i + 1] in "({":
            depth += 1
            i += 2
            continue
        if line[i] in ")}" and depth > 0:
            depth -= 1
        i += 1
    return depth


for i, line in enumerate(lines, 1):
    stripped = line.strip()
    if not stripped or stripped.startswith("#"):
        continue

    if re.match(r"^(ifeq|ifneq|ifdef|ifndef)\b", stripped):
        conds += 1
    elif re.match(r"^else\b", stripped):
        if conds == 0:
            errors.append(f"{i}: else 出现在条件块之外")
    elif re.match(r"^endif\b", stripped):
        conds -= 1
        if conds < 0:
            errors.append(f"{i}: endif 多余")
    if re.match(r"^define\b", stripped):
        defines += 1
    elif re.match(r"^endef\b", stripped):
        defines -= 1
        if defines < 0:
            errors.append(f"{i}: endef 多余")
        continue

    # 续行会让单行看起来不配平，只在行尾没有续行符时判定
    if not line.rstrip().endswith("\\"):
        net = paren_balance(line)
        if net != 0:
            errors.append(f"{i}: $() 括号不配平（net={net}）: {stripped[:70]}")

    if line[0] == " " and in_rule:
        errors.append(
            f"{i}: 规则上下文里用空格缩进（make 会报 missing separator）: {stripped[:70]}"
        )

    in_rule = bool(rule_re.match(line)) or line.startswith("\t")
    if rule_re.match(line):
        targets.append((i, line.split(":", 1)[0].strip()))

if conds != 0:
    errors.append(f"条件块未闭合：ifneq/ifeq 深度={conds}")
if defines != 0:
    errors.append(f"define 未闭合：深度={defines}")

src = open(path, encoding="utf-8").read()
evals = re.findall(r"\$\(eval \$\(call PACC_BPF_RULE,([\w-]+),", src)
if len(evals) != 3:
    errors.append(f"期望 3 条 PACC_BPF_RULE 展开，实际 {len(evals)}")
if "$(BPF_BUILD)/%.$(1).bpf.o:" not in src:
    errors.append("PACC_BPF_RULE 的目标模式未参数化，三个变体会互相覆盖")
for v in evals:
    var = {"perf": "perf", "ringbuf": "ringbuf", "perf-compat54": "compat54"}.get(v)
    if var is None:
        errors.append(f"未知变体名 {v}")
    elif f"BPF_OBJS_{var}" not in src:
        errors.append(f"变体 {v} 没有对应的产物变量 BPF_OBJS_{var}")

print(f"[lint] 文件: {path}")
print(f"[lint] 规则数: {len(targets)}；PACC_BPF_RULE 变体: {evals}")
for ln, t in targets:
    print(f"[lint]   第 {ln} 行 target: {t}")
if errors:
    print(f"[lint] 发现 {len(errors)} 个问题：")
    for e in errors:
        print(f"[lint]   - {e}")
    sys.exit(1)
print("[lint] 结构检查通过（注意：这不等于 make 能正确执行）")