"""bsdiff 补丁的生成与应用（标准库实现）。

格式与 libbsdiff 一致：

    offset 0    8 字节  魔数 "BSDIFF40"
    offset 8    8 字节  CTRL 块压缩后的长度
    offset 16   8 字节  DIFF 块压缩后的长度
    offset 24   8 字节  新文件长度
    offset 32           CTRL / DIFF / EXTRA 三个独立 bzip2 流，依次排列

三个长度都是 bsdiff 的 off_t：小端 63 位量值，最高位当符号位。

CTRL 块是 (x, y, z) 三元组序列，语义是「从旧文件当前偏移复制 x 字节（逐字节加上
DIFF 块里的差值）/ 插入 y 字节（取自 EXTRA 块）/ 旧文件偏移再挪 z（可为负）」，
循环直到新文件装满。
"""

from __future__ import annotations

import bz2

MAGIC = b"BSDIFF40"
HEADER_SIZE = 32
TUPLE_SIZE = 24

# 生成端找匹配用的窗口长度。太短会在随机数据里撞出假匹配，太长则短重复段匹配不上。
WINDOW = 16


def _u64(value: int) -> bytes:
    return int(value).to_bytes(8, "little")


def read_offset(buf: bytes, off: int) -> int:
    """读一个 off_t（小端 63 位量值 + 最高位符号位）。"""
    if off + 8 > len(buf):
        raise ValueError("读取 off_t 越界")
    raw = int.from_bytes(buf[off:off + 8], "little")
    sign_bit = 1 << 63
    magnitude = raw & (sign_bit - 1)
    return -magnitude if raw & sign_bit else magnitude


def write_offset(value: int) -> bytes:
    """写一个 off_t。"""
    sign_bit = 1 << 63
    if value < 0:
        return _u64(sign_bit | (-value & (sign_bit - 1)))
    return _u64(value & (sign_bit - 1))


def _assemble(ctrl: bytes, diff: bytes, extra: bytes, new_size: int) -> bytes:
    ctrl_z = bz2.compress(ctrl)
    diff_z = bz2.compress(diff)
    extra_z = bz2.compress(extra)
    return (
        MAGIC
        + _u64(len(ctrl_z))
        + _u64(len(diff_z))
        + _u64(new_size)
        + ctrl_z
        + diff_z
        + extra_z
    )


def make_patch(old: bytes, new: bytes) -> bytes:
    """生成 ``old -> new`` 的 BSDIFF40 补丁（朴素贪心，保证确定性）。

    旧文件里所有长度为 WINDOW 的窗口进索引（同键保留首次出现位置），在 ``new`` 上
    从左往右扫，命中就向后尽可能延长匹配，落成「先插入待定段并 seek、再复制」两个
    三元组；没命中的字节积累成待插入段。
    """
    index: dict[bytes, int] = {}
    if len(old) >= WINDOW:
        for pos in range(0, len(old) - WINDOW + 1):
            index.setdefault(old[pos:pos + WINDOW], pos)

    ctrl = bytearray()
    diff = bytearray()
    extra = bytearray()
    old_pos = 0
    pending = 0  # 待插入段在 new 里的起点
    i = 0
    while i < len(new):
        pos = index.get(new[i:i + WINDOW]) if i + WINDOW <= len(new) else None
        if pos is None:
            i += 1
            continue
        mlen = WINDOW
        while (pos + mlen < len(old) and i + mlen < len(new)
               and old[pos + mlen] == new[i + mlen]):
            mlen += 1
        insert_len = i - pending
        if insert_len > 0 or pos != old_pos:
            ctrl += write_offset(0)
            ctrl += write_offset(insert_len)
            ctrl += write_offset(pos - old_pos)
            extra += new[pending:i]
            old_pos = pos
        ctrl += write_offset(mlen)
        ctrl += write_offset(0)
        ctrl += write_offset(0)
        for k in range(mlen):
            diff.append((new[i + k] - old[pos + k]) & 0xFF)
        i += mlen
        old_pos += mlen
        pending = i

    insert_len = len(new) - pending
    if insert_len > 0:
        ctrl += write_offset(0)
        ctrl += write_offset(insert_len)
        ctrl += write_offset(0)
        extra += new[pending:]
    return _assemble(bytes(ctrl), bytes(diff), bytes(extra), len(new))


def apply_patch(old: bytes, patch: bytes) -> bytes:
    """把补丁打到旧文件上，还原出新文件。

    校验从严：魔数不对、长度非法、三元组不够、复制或插入越过可用数据，都抛
    ``ValueError``，不静默截断也不补零。
    """
    if len(patch) < HEADER_SIZE:
        raise ValueError("补丁长度不足，装不下 BSDIFF40 头")
    if patch[:8] != MAGIC:
        raise ValueError("补丁魔数不是 BSDIFF40")

    ctrl_len = read_offset(patch, 8)
    diff_len = read_offset(patch, 16)
    new_size = read_offset(patch, 24)
    if ctrl_len < 0 or diff_len < 0 or new_size < 0:
        raise ValueError("补丁声明了负长度")
    if HEADER_SIZE + ctrl_len + diff_len > len(patch):
        raise ValueError("补丁声明的 CTRL/DIFF 长度超出实际数据")

    diff_start = HEADER_SIZE + ctrl_len
    extra_start = diff_start + diff_len
    ctrl = bz2.decompress(patch[HEADER_SIZE:diff_start])
    diff = bz2.decompress(patch[diff_start:extra_start])
    extra = bz2.decompress(patch[extra_start:])

    out = bytearray(new_size)
    old_pos = 0
    new_pos = 0
    ctrl_pos = 0
    diff_pos = 0
    extra_pos = 0
    while new_pos < new_size:
        if ctrl_pos + TUPLE_SIZE > len(ctrl):
            raise ValueError("CTRL 块不完整，缺少三元组")
        copy_len = read_offset(ctrl, ctrl_pos)
        insert_len = read_offset(ctrl, ctrl_pos + 8)
        seek_len = read_offset(ctrl, ctrl_pos + 16)
        ctrl_pos += TUPLE_SIZE
        if copy_len < 0 or insert_len < 0:
            raise ValueError("三元组出现负的复制/插入长度")
        if new_pos + copy_len > new_size:
            raise ValueError("复制长度超出新文件长度")
        if diff_pos + copy_len > len(diff):
            raise ValueError("DIFF 块数据不足")
        if copy_len > 0:
            if old_pos < 0 or old_pos + copy_len > len(old):
                raise ValueError("复制区间超出旧文件范围")
            for k in range(copy_len):
                out[new_pos + k] = (diff[diff_pos + k] + old[old_pos + k]) & 0xFF
        new_pos += copy_len
        old_pos += copy_len
        diff_pos += copy_len

        if new_pos + insert_len > new_size:
            raise ValueError("插入长度超出新文件长度")
        if extra_pos + insert_len > len(extra):
            raise ValueError("EXTRA 块数据不足")
        out[new_pos:new_pos + insert_len] = extra[extra_pos:extra_pos + insert_len]
        new_pos += insert_len
        extra_pos += insert_len
        old_pos += seek_len
    return bytes(out)