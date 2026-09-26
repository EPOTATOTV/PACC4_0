"""Java 代码生成。

生成物是入库的（设计文档 §3.7 要求生成结果进版本库，便于 review 与 diff），所以输出
必须逐字节确定：不写生成时间、不依赖字典遍历顺序、字段按编号升序排列。CI 用
`python -m pbpgen --check` 比对，任何非确定性都会变成天天飘红的假告警。

有意偏离设计文档 §3.6.2 的一条：**不做「末尾字段自动 optional」**。按那个规则，末尾一个
`uint8` 会凭空多出一个存在性字节，既没有语义（那个字节永远不会置位），又让「在末尾加
字段」这件事在线上多出一字节的歧义。可空性只由显式 `// optional` 声明，且仅限
string / bytes / 消息类型。

生成类的形态与 protobuf 的 Java 生成物对齐（newBuilder / toBuilder / getX），目的是让
上层调用点不用跟着改：MDL 里的 `ts_ms` 生成 `getTsMs()`，迁移就是换 import。
"""

from __future__ import annotations

from ..model import Enum, Message, ResolvedField, ResolvedType, Schema, id_range_label

_GENERATOR_NOTE = "本文件由 tools/pbpgen 生成，请勿手改。"

_BOXED = {
    "boolean": "Boolean",
    "int": "Integer",
    "long": "Long",
    "float": "Float",
    "double": "Double",
    "String": "String",
    "byte[]": "byte[]",
}


def _boxed(java: str) -> str:
    """集合元素的类型名：标量按装箱类型，消息类型本身就是引用类型，原样使用。"""
    return _BOXED.get(java, java)


_DEFAULTS = {
    "boolean": "false",
    "int": "0",
    "long": "0L",
    "float": "0.0f",
    "double": "0.0d",
    "String": '""',
    "byte[]": "new byte[0]",
}

_PRIMITIVES = frozenset({"boolean", "int", "long", "float", "double"})


def emit_schema(schema: Schema, mdl_rel: str) -> dict[str, str]:
    """返回 {相对 Java 源码根的路径: 文件内容}。"""
    files: dict[str, str] = {}
    prefix = schema.java_package.replace(".", "/")
    for enum in schema.enums:
        files[f"{prefix}/{enum.name}.java"] = _emit_enum(schema, enum, mdl_rel)
    for message in schema.messages:
        files[f"{prefix}/{message.name}.java"] = _emit_message(schema, message, mdl_rel)
    return files


def _header(schema: Schema, mdl_rel: str) -> list[str]:
    return [
        f"// {_GENERATOR_NOTE}",
        f"// 源定义：{mdl_rel}",
        "// 重新生成：cd tools/pbpgen && python -m pbpgen",
        "",
        f"package {schema.java_package};",
        "",
    ]


def _emit_enum(schema: Schema, enum: Enum, mdl_rel: str) -> str:
    lines = _header(schema, mdl_rel)
    lines.append("/**")
    lines.append(f" * MDL 枚举 {{@code {enum.name}}}。")
    lines.append(" *")
    lines.append(" * <p>枚举在线上是 VarInt 编码的非负整数，所以生成的是常量而不是 Java enum：")
    lines.append(" * 编解码两侧统一按 int 传递，省掉一层 value ↔ 常量的来回转换。</p>")
    lines.append(" */")
    lines.append(f"public final class {enum.name} {{")
    lines.append("")
    for name, number in enum.values:
        lines.append(f"    public static final int {name} = {number};")
    lines.append("")
    lines.append(f"    private {enum.name}() {{")
    lines.append("    }")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def _emit_message(schema: Schema, message: Message, mdl_rel: str) -> str:
    lines = _header(schema, mdl_rel)
    lines.append("/**")
    if message.framed:
        lines.append(f" * PBP 消息 {{@code {message.name}}}（{{@code 0x{message.message_id:04X}}}，"
                     f"{id_range_label(message.message_id)}）。")
    else:
        lines.append(f" * PBP 消息 {{@code {message.name}}}，无消息 ID，仅作为嵌套类型内联在父消息载荷里。")
    lines.append(" *")
    lines.append(" * <p>字段按编号升序写在载荷里、不带标签，所以调整字段顺序等同于改协议；")
    lines.append(" * 新字段只能追加在末尾，否则两侧解析会整体错位。</p>")
    if message.signed:
        lines.append(" *")
        lines.append(" * <p>签名不在载荷里：置位帧头 {@code FLAG_SIGNED}，32 字节 HMAC-SHA256 放在帧尾，")
        lines.append(" * 覆盖面是「帧头 + 载荷」整串字节（见 {@link #signingInput()}）。</p>")
    lines.append(" */")
    lines.append(f"public final class {message.name} implements PbpMessage {{")
    lines.append("")

    if message.framed:
        lines.append("    /** MDL 里声明的消息 ID。 */")
        lines.append(f"    public static final int MESSAGE_ID = 0x{message.message_id:04X};")
        lines.append("")
    if message.optional_field_count:
        lines.append("    /** 可空字段个数，用于定位载荷里的存在性位图。 */")
        lines.append(f"    public static final int OPTIONAL_FIELD_COUNT = {message.optional_field_count};")
        lines.append("")

    emitter = _MessageEmitter(schema, message)
    emitter.emit_class_body()
    lines.extend(emitter.lines)
    lines.append("}")
    lines.append("")
    return "\n".join(_with_imports(lines, message))


def _with_imports(lines: list[str], message: Message) -> list[str]:
    """按实际用到的 API 生成 import，不留未使用的 import。"""
    has_list = any(f.is_list for f in message.fields)
    has_map = any(f.is_map for f in message.fields)

    imports = [
        "import com.potatotv.pbp.PbpDecoder;",
        "import com.potatotv.pbp.PbpEncoder;",
    ]
    if message.framed:
        imports.append("import com.potatotv.pbp.PbpException;")
        imports.append("import com.potatotv.pbp.PbpFrame;")
    imports.append("import com.potatotv.pbp.PbpMessage;")

    java_imports: set[str] = set()
    if message.signed:
        java_imports.add("import java.util.HexFormat;")
    if has_list:
        java_imports.add("import java.util.ArrayList;")
        java_imports.add("import java.util.List;")
    if has_map:
        java_imports.add("import java.util.LinkedHashMap;")
        java_imports.add("import java.util.Map;")
    if has_list or has_map:
        # 集合 getter 一律返回不可变视图，避免调用方改到消息内部状态
        java_imports.add("import java.util.Collections;")

    head = lines[:6]
    body = lines[6:]
    out = list(head)
    out.extend(imports)
    if java_imports:
        out.append("")
        out.extend(sorted(java_imports))
    out.append("")
    out.extend(body)
    return out


class _MessageEmitter:
    """逐段拼出类体，缩进由 add() 的第二参数控制。"""

    def __init__(self, schema: Schema, message: Message):
        self.schema = schema
        self.message = message
        self.lines: list[str] = []

    # ------------------------------------------------------------ 工具

    def add(self, text: str = "", indent: int = 1) -> None:
        self.lines.append(("    " * indent + text) if text else "")

    def doc(self, text: str, indent: int = 1) -> None:
        self.add(f"/** {text} */", indent)

    def field_type(self, field: ResolvedField) -> str:
        if field.is_list:
            return f"List<{_boxed(field.value_type.java)}>"
        if field.is_map:
            return f"Map<{_boxed(field.map_key.java)}, {_boxed(field.value_type.java)}>"
        return field.value_type.java

    def default_expr(self, field: ResolvedField) -> str:
        if field.is_list:
            return "new ArrayList<>()"
        if field.is_map:
            return "new LinkedHashMap<>()"
        if field.value_type.kind == "message" or field.optional:
            return "null"
        return _DEFAULTS[field.value_type.java]

    def setter_expr(self, field: ResolvedField) -> str:
        if field.is_list:
            return "value == null ? new ArrayList<>() : new ArrayList<>(value)"
        if field.is_map:
            return "value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value)"
        if field.value_type.java == "byte[]":
            return "value == null ? new byte[0] : value.clone()"
        if field.value_type.java == "String" and not field.optional:
            return 'value == null ? "" : value'
        return "value"

    # ------------------------------------------------------------ 类体

    def emit_class_body(self) -> None:
        self._fields()
        self._builder()
        self._getters()
        if self.message.framed:
            self._frame_and_signature()
        self._core_methods()
        self._list_size_helpers()
        self._to_string()

    def _fields(self) -> None:
        message = self.message
        self.add("// ------------------------------------------------------------ 字段")
        self.add("")
        for field in message.fields:
            init = "" if (not field.is_list and not field.is_map
                          and field.value_type.java in _PRIMITIVES) else f" = {self.default_expr(field)}"
            self.add(f"private {self.field_type(field)} {field.field_name}{init};")
        if message.signed:
            self.add("")
            self.add("// 帧尾签名，载荷里没有这个字段")
            self.add("private byte[] signature = new byte[0];")
        self.add("")
        self.add("/** 解析路径用它建空对象，字段默认值见声明处。 */")
        # 包内可见而不是 private：消息被别的消息嵌套引用时，父消息里的
        # `dec.readMessage(Child::new)` 需要拿到这个构造器，private 编不过。
        self.add(f"{message.name}() {{")
        self.add("}")
        self.add("")

    def _builder(self) -> None:
        message = self.message
        self.add("/**")
        self.add(" * 字段构造器。可空性由 MDL 决定：String 与 byte[] 默认空值、")
        self.add(" * 消息类型字段默认 null（编码时才校验，为空直接抛 PbpException）。")
        self.add(" */")
        self.add("public static final class Builder {")
        self.add("")

        for field in message.fields:
            self.add(f"private {self.field_type(field)} {field.field_name} = {self.default_expr(field)};", 2)
        if message.signed:
            self.add("private byte[] signature = new byte[0];", 2)
        self.add("")
        self.add("private Builder() {", 2)
        self.add("}", 2)
        self.add("")

        for field in message.fields:
            self.doc(f"MDL 字段 {field.number} {{@code {field.name}}}。", 2)
            self.add(f"public Builder set{field.java_name}({self.field_type(field)} value) {{", 2)
            self.add(f"this.{field.field_name} = {self.setter_expr(field)};", 3)
            self.add("return this;", 3)
            self.add("}", 2)
            self.add("")
            if field.is_list:
                self.doc(f"追加一个 {{@code {field.name}}} 元素。", 2)
                self.add(f"public Builder add{field.java_name}({field.value_type.java} value) {{", 2)
                self.add(f"this.{field.field_name}.add(value);", 3)
                self.add("return this;", 3)
                self.add("}", 2)
                self.add("")
            if field.is_map:
                self.doc(f"写入一个 {{@code {field.name}}} 键值对。", 2)
                self.add(f"public Builder put{field.java_name}({field.map_key.java} key, "
                         f"{field.value_type.java} value) {{", 2)
                self.add(f"this.{field.field_name}.put(key, value);", 3)
                self.add("return this;", 3)
                self.add("}", 2)
                self.add("")

        if message.signed:
            self.doc("帧尾 32 字节 HMAC-SHA256 的小写十六进制；空串表示不签名。", 2)
            self.add("public Builder setSignature(String hex) {", 2)
            self.add("this.signature = signatureFromHex(hex);", 3)
            self.add("return this;", 3)
            self.add("}", 2)
            self.add("")
            self.doc("帧尾签名的原始字节；null 视为不签名。", 2)
            self.add("public Builder setSignatureBytes(byte[] value) {", 2)
            self.add("this.signature = value == null ? new byte[0] : value.clone();", 3)
            self.add("return this;", 3)
            self.add("}", 2)
            self.add("")

        self.add(f"public {message.name} build() {{", 2)
        self.add(f"{message.name} msg = new {message.name}();", 3)
        for field in message.fields:
            if field.is_list:
                self.add(f"msg.{field.field_name} = new ArrayList<>({field.field_name});", 3)
            elif field.is_map:
                self.add(f"msg.{field.field_name} = new LinkedHashMap<>({field.field_name});", 3)
            elif field.value_type.java == "byte[]":
                self.add(f"msg.{field.field_name} = {field.field_name}.clone();", 3)
            else:
                self.add(f"msg.{field.field_name} = {field.field_name};", 3)
        if message.signed:
            self.add("msg.signature = signature.clone();", 3)
        self.add("return msg;", 3)
        self.add("}", 2)
        self.add("}")
        self.add("")

        self.add("public static Builder newBuilder() {")
        self.add("return new Builder();", 2)
        self.add("}", 1)
        self.add("")

        self.doc("以当前值为初值开一个新 Builder（例如改完字段要重新签名）。")
        self.add("public Builder toBuilder() {")
        self.add("Builder builder = new Builder();", 2)
        for field in message.fields:
            if field.is_list:
                self.add(f"builder.{field.field_name} = new ArrayList<>({field.field_name});", 2)
            elif field.is_map:
                self.add(f"builder.{field.field_name} = new LinkedHashMap<>({field.field_name});", 2)
            elif field.value_type.java == "byte[]":
                self.add(f"builder.{field.field_name} = {field.field_name}.clone();", 2)
            else:
                self.add(f"builder.{field.field_name} = {field.field_name};", 2)
        if message.signed:
            self.add("builder.signature = signature.clone();", 2)
        self.add("return builder;", 2)
        self.add("}", 1)
        self.add("")

    def _getters(self) -> None:
        self.add("// ------------------------------------------------------------ 字段读取")
        self.add("")
        for field in self.message.fields:
            self.doc(f"MDL 字段 {field.number} {{@code {field.name}}}。")
            self.add(f"public {self.field_type(field)} get{field.java_name}() {{")
            if field.is_list:
                self.add(f"return Collections.unmodifiableList({field.field_name});", 2)
            elif field.is_map:
                self.add(f"return Collections.unmodifiableMap({field.field_name});", 2)
            elif field.value_type.java == "byte[]":
                self.add(f"return {field.field_name}.clone();", 2)
            else:
                self.add(f"return {field.field_name};", 2)
            self.add("}", 1)
            self.add("")

    def _frame_and_signature(self) -> None:
        message = self.message
        frame_ts = message.frame_timestamp_field.field_name if message.frame_timestamp_field else "0L"

        self.add("// ------------------------------------------------------------ 帧")
        self.add("")
        if message.signed:
            self.doc("编码为完整帧；已签名时置位 FLAG_SIGNED 并把 32 字节签名放到帧尾。")
        else:
            self.doc("编码为完整帧。本消息不签名，帧头时间戳与序列号由上层填写。")
        self.add("public byte[] toByteArray() {")
        self.add(f"PbpFrame frame = PbpFrame.of(MESSAGE_ID, {frame_ts}, payloadBytes());", 2)
        if message.signed:
            self.add("if (signature.length == PbpFrame.SIGNATURE_SIZE) {", 2)
            self.add("frame = frame.withSignature(signature);", 3)
            self.add("} else if (signature.length != 0) {", 2)
            self.add("throw new PbpException(PbpException.Code.BAD_LENGTH,", 3)
            self.add('"签名长度必须是 0 或 " + PbpFrame.SIGNATURE_SIZE + "，实际 " + signature.length);', 4)
            self.add("}", 2)
        self.add("return frame.encode();", 2)
        self.add("}", 1)
        self.add("")

        if message.signed:
            self.doc("HMAC 覆盖面：帧头（FLAG_SIGNED 已置位）+ 载荷。")
            self.add("public byte[] signingInput() {")
            self.add(f"return PbpFrame.of(MESSAGE_ID, {frame_ts}, payloadBytes()).signingInput();", 2)
            self.add("}", 1)
            self.add("")

        self.doc("解析完整帧；帧结构非法或消息 ID 不符一律抛 PbpException。")
        self.add(f"public static {message.name} parseFrom(byte[] raw) {{")
        self.add("PbpFrame frame = PbpFrame.parse(raw);", 2)
        self.add("if (frame.messageId() != MESSAGE_ID) {", 2)
        self.add("throw new PbpException(PbpException.Code.BAD_FORMAT,", 3)
        self.add('"消息 ID 不符：期望 0x" + Integer.toHexString(MESSAGE_ID)', 4)
        self.add('+ "，实际 0x" + Integer.toHexString(frame.messageId()));', 4)
        self.add("}", 2)
        self.add(f"{message.name} msg = new {message.name}();", 2)
        self.add("msg.decode(new PbpDecoder(frame.payload()));", 2)
        if message.signed:
            self.add("msg.signature = frame.signature().clone();", 2)
        self.add("return msg;", 2)
        self.add("}", 1)
        self.add("")

        self.add("private byte[] payloadBytes() {")
        self.add("PbpEncoder enc = new PbpEncoder(encodedSize());", 2)
        self.add("encode(enc);", 2)
        self.add("return enc.toByteArray();", 2)
        self.add("}", 1)
        self.add("")

        if not message.signed:
            return

        self.add("// ------------------------------------------------------------ 签名")
        self.add("")
        self.doc("帧尾 32 字节 HMAC-SHA256 的小写十六进制；未签名返回空串。")
        self.add("public String getSignature() {")
        self.add('return signature.length == 0 ? "" : HexFormat.of().formatHex(signature);', 2)
        self.add("}", 1)
        self.add("")
        self.doc("帧尾签名的副本；未签名返回零长数组。")
        self.add("public byte[] signatureBytes() {")
        self.add("return signature.clone();", 2)
        self.add("}", 1)
        self.add("")
        self.add("private static byte[] signatureFromHex(String hex) {")
        self.add("if (hex == null || hex.isEmpty()) {", 2)
        self.add("return new byte[0];", 3)
        self.add("}", 2)
        self.add("try {", 2)
        self.add("return HexFormat.of().parseHex(hex);", 3)
        self.add("} catch (IllegalArgumentException e) {", 2)
        self.add('throw new PbpException(PbpException.Code.BAD_FORMAT, "签名不是合法十六进制串", e);', 3)
        self.add("}", 2)
        self.add("}", 1)
        self.add("")

    def _core_methods(self) -> None:
        message = self.message
        self.add("// ------------------------------------------------------------ PbpMessage")
        self.add("")
        self.add("@Override")
        self.add("public int messageId() {")
        self.add("return MESSAGE_ID;" if message.framed else "return 0;", 2)
        self.add("}", 1)
        self.add("")

        self.add("@Override")
        self.add("public void encode(PbpEncoder enc) {")
        for field in message.fields:
            self.add(f"enc.{_encode_call(field)};", 2)
        self.add("}", 1)
        self.add("")

        self.doc("按定义顺序读回字段。载荷尾部若有多出的字节（新端追加了字段）不再消费，"
                  "这是向前兼容的落点。")
        self.add("@Override")
        self.add("public void decode(PbpDecoder dec) {")
        for field in message.fields:
            self.add(f"{field.field_name} = {_decode_expr(field)};", 2)
        self.add("}", 1)
        self.add("")

        self.doc("编码后的字节数，仅用于预分配缓冲区。")
        self.add("@Override")
        self.add("public int encodedSize() {")
        self.add("int size = 0;", 2)
        for field in message.fields:
            self.add(f"size += {_size_expr(field)};", 2)
        self.add("return size;", 2)
        self.add("}", 1)
        self.add("")

    def _list_size_helpers(self) -> None:
        fields = [
            f for f in self.message.fields
            if f.is_list and not (f.value_type.kind == "message" or f.value_type.name == "string")
        ]
        for field in fields:
            element_type = field.value_type.java
            self.doc(f"{{@code {field.name}}} 列表的编码长度：PbpEncoder 只内置了 string/message 列表的大小计算。")
            self.add(f"private static int sizeOf{field.java_name}(List<{_boxed(element_type)}> list) {{")
            self.add("int size = PbpEncoder.varIntSize(list.size());", 2)
            self.add(f"for ({element_type} element : list) {{", 2)
            self.add(f"size += {_element_size_expr(field.value_type)};", 3)
            self.add("}", 2)
            self.add("return size;", 2)
            self.add("}", 1)
            self.add("")

    def _to_string(self) -> None:
        parts = [f'"{field.name}=" + {_to_string_expr(field)}' for field in self.message.fields]
        self.add("@Override")
        self.add("public String toString() {")
        body = ' + ", " + '.join(parts)
        self.add(f'return "{self.message.name}{{" + {body} + "}}";', 2)
        self.add("}", 1)
        self.add("")


# ---------------------------------------------------------------- 表达式


def _to_string_expr(field: ResolvedField) -> str:
    if field.is_list or field.is_map:
        return f'{field.field_name}.size() + " 项"'
    if field.value_type.java == "byte[]":
        return f'{field.field_name}.length + " 字节"'
    return field.field_name


def _encode_call(field: ResolvedField) -> str:
    value = field.value_type
    name = field.field_name
    if field.is_map:
        writer = f"PbpEncoder::{value.writer}"
        if field.map_key.name == "string":
            return f"writeStringMap({name}, {writer})"
        return f"writeMap({name}, PbpEncoder::{field.map_key.writer}, {writer})"
    if field.is_list:
        if value.kind == "message":
            return f"writeMessageList({name})"
        if value.name == "string":
            return f"writeStringList({name})"
        return f"writeList({name}, PbpEncoder::{value.writer})"
    if field.optional:
        if value.kind == "message":
            return f"writeOptionalMessage({name})"
        if value.name == "string":
            return f"writeOptionalString({name})"
        return f"writeOptionalBytes({name})"
    if value.kind == "message":
        return f"writeMessage({name})"
    return f"{value.writer}({name})"


def _decode_expr(field: ResolvedField) -> str:
    value = field.value_type
    if field.is_map:
        if field.map_key.name == "string":
            if value.kind == "message":
                return (f"dec.readMap(LinkedHashMap::new, PbpDecoder::readString, "
                        f"d -> d.readMessage({value.name}::new))")
            return f"dec.readStringMap(PbpDecoder::{value.reader})"
        if value.kind == "message":
            return (f"dec.readMap(LinkedHashMap::new, PbpDecoder::{field.map_key.reader}, "
                    f"d -> d.readMessage({value.name}::new))")
        return (f"dec.readMap(LinkedHashMap::new, PbpDecoder::{field.map_key.reader}, "
                f"PbpDecoder::{value.reader})")
    if field.is_list:
        if value.kind == "message":
            return f"dec.readMessageList({value.name}::new)"
        if value.name == "string":
            return "dec.readStringList()"
        return f"dec.readList(ArrayList::new, PbpDecoder::{value.reader})"
    if field.optional:
        if value.kind == "message":
            return f"dec.readOptionalMessage({value.name}::new)"
        if value.name == "string":
            return "dec.readOptionalString()"
        return "dec.readOptionalBytes()"
    if value.kind == "message":
        return f"dec.readMessage({value.name}::new)"
    return f"dec.{value.reader}()"


def _size_expr(field: ResolvedField) -> str:
    value = field.value_type
    name = field.field_name
    if field.is_map:
        if field.map_key.name == "string":
            return f"PbpEncoder.stringMapSize({name}, {_sizer_fn(value, 'v')})"
        return f"PbpEncoder.mapSize({name}, {_sizer_fn(field.map_key, 'k')}, {_sizer_fn(value, 'v')})"
    if field.is_list:
        if value.kind == "message":
            return f"PbpEncoder.messageListSize({name})"
        if value.name == "string":
            return f"PbpEncoder.stringListSize({name})"
        return f"sizeOf{field.java_name}({name})"
    if field.optional:
        if value.kind == "message":
            return f"PbpEncoder.optionalMessageSize({name})"
        if value.name == "string":
            return f"PbpEncoder.optionalStringSize({name})"
        return f"PbpEncoder.optionalBytesSize({name})"
    if value.kind == "message":
        return f"PbpEncoder.messageSize({name})"
    if value.sizer_takes_value:
        return f"PbpEncoder.{value.sizer}({name})"
    return f"PbpEncoder.{value.sizer}()"


def _element_size_expr(value: ResolvedType) -> str:
    if value.sizer_takes_value:
        return f"PbpEncoder.{value.sizer}(element)"
    return f"PbpEncoder.{value.sizer}()"


def _sizer_fn(value: ResolvedType, param: str) -> str:
    """方法引用优先；无参的定长 sizer 只能用 lambda 去适配 ToIntFunction。"""
    if value.sizer_takes_value:
        return f"PbpEncoder::{value.sizer}"
    return f"{param} -> PbpEncoder.{value.sizer}()"