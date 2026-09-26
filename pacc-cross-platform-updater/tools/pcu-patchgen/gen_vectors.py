#!/usr/bin/env python3
"""生成 PCU 差分补丁的金标向量，或校验磁盘上的向量与重新生成的结果是否一致。

用法（在 tools/pcu-patchgen 目录下）：

    python gen_vectors.py                 # 生成到 ../../src/test/resources/vectors
    python gen_vectors.py --check         # 只校验，不一致非 0 退出

和 ``python -m pcu_patchgen`` 等价，保留本文件是为了让人直接看到「向量是怎么来的」。
退出码：0 通过；1 校验失败；2 自检或 IO 失败。
"""

from __future__ import annotations

import sys

from pcu_patchgen.__main__ import main

if __name__ == "__main__":
    sys.exit(main())