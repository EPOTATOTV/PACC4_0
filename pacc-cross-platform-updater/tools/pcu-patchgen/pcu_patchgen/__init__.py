"""PCU 差分补丁（bsdiff/bspatch）的 python 参考实现与金标向量生成。

只依赖标准库（``bz2`` / ``struct`` / ``hashlib``），用途有两个：

1. Java 侧的 ``com.potatotv.pcu.BZip2`` / ``BsDiff`` / ``BsPatch`` 是自研实现，
   必须能跟参考实现对得上。这里的 ``bspatch`` 应用端完整可用，用来接住 Java 侧
   生成的补丁；
2. ``gen_vectors.py`` / ``python -m pcu_patchgen`` 生成确定性的金标向量，交给 Java
   测试遍历应用，覆盖「完全相同 / 小改动 / 中部插入 / 尾部追加 / 整体不同 /
   空文件→非空」这些场景。

生成端（``make_patch``）走朴素策略：拿旧文件的 16 字节窗口建索引，在旧文件里找
匹配段，落成标准 (x, y, z) 三元组。不追求差分率，只要补丁合法、能被标准 bspatch
应用即可。
"""

from .bspatch import apply_patch, make_patch, read_offset, write_offset
from .vectors import DEFAULT_VECTOR_DIR, check_vectors, generate_vectors, scenario_names

__all__ = [
    "apply_patch",
    "make_patch",
    "read_offset",
    "write_offset",
    "DEFAULT_VECTOR_DIR",
    "check_vectors",
    "generate_vectors",
    "scenario_names",
]