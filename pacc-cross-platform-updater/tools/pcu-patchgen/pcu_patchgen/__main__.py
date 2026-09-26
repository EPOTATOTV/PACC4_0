"""``python -m pcu_patchgen``：生成 / 校验金标向量。

退出码：0 通过；1 校验失败；2 用法或自检异常。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .vectors import DEFAULT_VECTOR_DIR, check_vectors, generate_vectors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="pcu_patchgen", description=__doc__)
    parser.add_argument("--out", default=str(DEFAULT_VECTOR_DIR),
                        help="向量输出目录（默认 src/test/resources/vectors）")
    parser.add_argument("--check", action="store_true",
                        help="只校验：重新生成并与磁盘上的向量逐字节比对")
    args = parser.parse_args(argv)
    out_dir = Path(args.out)

    try:
        if args.check:
            problems = check_vectors(out_dir)
            if problems:
                for line in problems:
                    print(f"[fail] {line}", file=sys.stderr)
                print(f"[fail] 金标向量校验失败，共 {len(problems)} 处不一致", file=sys.stderr)
                return 1
            print(f"[pass] 金标向量与重新生成的结果一致：{out_dir}")
            return 0
        count = generate_vectors(out_dir)
        print(f"[pass] 写出金标向量 {count} 个文件 -> {out_dir}")
        return 0
    except Exception as exc:  # noqa: BLE001 - 参考实现自检或 IO 失败，直接报非零
        print(f"[fail] {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())