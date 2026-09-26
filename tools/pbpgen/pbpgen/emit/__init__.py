"""Java 生成器出口。

目前只接 Java：TypeScript / Rust / C# / Python 的 emitter 属于设计文档 §3.7 的后续阶段，
等真正有消费方时再加。`emit_schema` 的返回结构（相对路径 → 文件内容）已经按多语言预留，
加 emitter 时不需要动编排层。
"""

from __future__ import annotations

from ..model import Schema
from .java import emit_schema as _emit_java

__all__ = ["emit_schema"]


def emit_schema(schema: Schema, mdl_rel: str) -> dict[str, str]:
    return _emit_java(schema, mdl_rel)