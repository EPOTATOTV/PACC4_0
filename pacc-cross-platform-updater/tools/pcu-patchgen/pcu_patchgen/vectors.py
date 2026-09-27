"""金标向量的构造与落盘。

向量是「一对旧/新文件 + 一条由本参考实现生成的补丁」，交给 Java 侧的
``BsPatchTest`` 遍历应用。内容用固定种子生成，同样的输入永远得到同样的字节，
所以 ``--check`` 能把「向量被人手改过」和「生成端逻辑变了」都抓出来。

场景覆盖设计要求里点名的六种情形，外加一组 ``sample``（体积稍大、改动混合了
插入与追加，用来按字节比对 sha256）。
"""

from __future__ import annotations

import hashlib
import random
from pathlib import Path

from .bspatch import apply_patch, make_patch

# 向量目录：模块根 / src/test/resources/vectors
DEFAULT_VECTOR_DIR = (
    Path(__file__).resolve().parents[3] / "src" / "test" / "resources" / "vectors"
)

SEED = 0x50414343  # 'PACC'
SAMPLE_NAME = "sample"


def _payload(size: int, seed: int) -> bytes:
    rnd = random.Random(seed)
    return bytes(rnd.randrange(256) for _ in range(size))


def _small_change(base: bytes, seed: int) -> bytes:
    """改几个字节，再原地替换一小段——典型的「版本微调」。"""
    out = bytearray(base)
    for pos in (100, 4000, 9000, len(base) - 3):
        out[pos] ^= 0x5A
    patch = _payload(32, seed)
    start = len(base) // 2
    out[start:start + len(patch)] = patch
    return bytes(out)


def _sample() -> tuple[bytes, bytes]:
    """sample 组：中部插入一段 + 尾部追加一段，模拟真实的制品升级。"""
    old = _payload(20480, 21)
    inserted = _payload(1536, 210)
    appended = _payload(512, 211)
    new = old[:8192] + inserted + old[8192:18432] + appended + old[18432:]
    return old, new


def build_scenarios() -> list[tuple[str, bytes, bytes]]:
    """按固定顺序返回 (名字, 旧文件, 新文件)。"""
    identical = _payload(4096, 11)
    small_base = _payload(16384, 12)
    middle_base = _payload(12288, 13)
    tail_base = _payload(8192, 14)
    sample_old, sample_new = _sample()
    return [
        ("identical", identical, identical),
        ("small-change", small_base, _small_change(small_base, 120)),
        ("middle-insert", middle_base,
         middle_base[:6144] + _payload(2048, 130) + middle_base[6144:]),
        ("tail-append", tail_base, tail_base + _payload(3072, 140)),
        ("totally-different", _payload(6144, 15), _payload(6144, 16)),
        ("empty-to-nonempty", b"", _payload(2560, 17)),
        (SAMPLE_NAME, sample_old, sample_new),
    ]


def scenario_names() -> list[str]:
    return [name for name, _old, _new in build_scenarios()]


def build_vectors() -> dict[str, bytes]:
    """算出所有向量文件的内容：文件名 -> 内容。"""
    files: dict[str, bytes] = {}
    for name, old, new in build_scenarios():
        files[f"{name}-old.bin"] = old
        files[f"{name}-new.bin"] = new
        files[f"{name}.patch"] = make_patch(old, new)
    digest = hashlib.sha256(files[f"{SAMPLE_NAME}-new.bin"]).hexdigest()
    files[f"{SAMPLE_NAME}-new.sha256"] = f"{digest}  {SAMPLE_NAME}-new.bin\n".encode("utf-8")
    return files


def _self_check(problems: list[str]) -> None:
    """先自证：每个补丁都能把旧文件还原成新文件，不然向量本身就是错的。"""
    for name, old, new in build_scenarios():
        try:
            got = apply_patch(old, make_patch(old, new))
        except Exception as exc:  # noqa: BLE001 - 收集失败继续跑完
            problems.append(f"{name}: 参考实现应用自己的补丁失败: {exc}")
            continue
        if got != new:
            problems.append(f"{name}: 参考实现往返不一致")


def generate_vectors(out_dir: Path) -> int:
    """把向量写到 ``out_dir``，返回写出的文件数。"""
    problems: list[str] = []
    _self_check(problems)
    if problems:
        raise RuntimeError("参考实现自检失败：" + "; ".join(problems))
    files = build_vectors()
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, content in sorted(files.items()):
        (out_dir / name).write_bytes(content)
    return len(files)


def check_vectors(out_dir: Path) -> list[str]:
    """重新生成并与磁盘上的向量逐字节比对，返回问题清单（空表示一致）。"""
    problems: list[str] = []
    _self_check(problems)
    files = build_vectors()
    for name, content in sorted(files.items()):
        path = out_dir / name
        if not path.is_file():
            problems.append(f"{name}: 向量文件缺失")
            continue
        actual = path.read_bytes()
        if actual != content:
            problems.append(f"{name}: 内容与重新生成的不一致"
                            f"（磁盘 {len(actual)} 字节，应为 {len(content)} 字节）")
    return problems