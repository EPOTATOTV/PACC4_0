package com.potatotv.prl.bytecode;

import com.potatotv.prl.PrlException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code .prlc} 文件的读写（设计文档 §2.8.1）。
 *
 * <p>文件头严格照文档：Magic、Version、Flags、Rule Count、以及 String Table / Code Section /
 * Metadata 三段的 offset+size 六个数。三段本身的内部布局文档没写，这里定成下面这样：</p>
 *
 * <pre>
 * Code Section:
 *   u16 函数数量
 *   每个函数:
 *     u16 函数名下标
 *     u8  flags（bit0 = 规则入口）
 *     u8  寄存器个数
 *     u8  形参个数
 *     u8  捕获个数
 *     u32 字节码相对 Code Section 起点的偏移
 *     u32 字节码长度
 *     u16 形参名下标 × 形参个数
 *     u16 捕获名下标 × 捕获个数
 *   u16 规则数量
 *   每条规则:
 *     u16 规则名下标
 *     u16 入口函数下标
 *
 * Metadata（JSON）:
 *   {"rules": [{"name": ..., "version": ..., "severity": ..., ...}, ...]}
 * </pre>
 *
 * <p>文档没有规定函数表放在哪，但 lambda 编成了嵌套函数、{@code CLOSURE} 需要按名找到它们，
 * 所以 Code Section 里必须带一张表；文件头因此保持原样不动。</p>
 */
public final class PrlcFormat {

    private static final byte[] MAGIC = {'P', 'R', 'L', 'C'};

    /** 文件头长度：4+2+1+2 + 6×4。 */
    private static final int HEADER_SIZE = 4 + 2 + 1 + 2 + 6 * 4;

    /** flags 位：规则入口。 */
    private static final int FLAG_ENTRY = 0x01;

    private PrlcFormat() {
    }

    // ------------------------------------------------------------------ 写

    public static byte[] write(PrlBytecode bytecode) {
        byte[] stringTable = writeStringTable(bytecode.strings());
        byte[] codeSection = writeCodeSection(bytecode);
        byte[] metadata = writeMetadata(bytecode);

        int stringOffset = HEADER_SIZE;
        int codeOffset = stringOffset + stringTable.length;
        int metadataOffset = codeOffset + codeSection.length;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        writeShort(out, PrlBytecode.FORMAT_VERSION);
        writeByte(out, 0);
        writeShort(out, bytecode.rules().size());
        writeInt(out, stringOffset);
        writeInt(out, stringTable.length);
        writeInt(out, codeOffset);
        writeInt(out, codeSection.length);
        writeInt(out, metadataOffset);
        writeInt(out, metadata.length);
        out.writeBytes(stringTable);
        out.writeBytes(codeSection);
        out.writeBytes(metadata);
        return out.toByteArray();
    }

    private static byte[] writeStringTable(List<String> strings) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String text : strings) {
            out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
            writeByte(out, 0);
        }
        return out.toByteArray();
    }

    private static byte[] writeCodeSection(PrlBytecode bytecode) {
        List<BytecodeFunction> functions = bytecode.functions();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, functions.size());

        // 字节码紧跟在函数表之后，先算表长以便写偏移。
        int tableSize = 0;
        for (BytecodeFunction function : functions) {
            tableSize += 2 + 1 + 1 + 1 + 1 + 4 + 4
                    + function.params().size() * 2 + function.captures().size() * 2;
        }
        int tableSizeBytes = 2 + tableSize + 2 + bytecode.rules().size() * (2 + 2);
        int bodyOffset = tableSizeBytes;

        for (BytecodeFunction function : functions) {
            writeShort(out, indexOf(bytecode, function.name()));
            writeByte(out, function.entry() ? FLAG_ENTRY : 0);
            writeByte(out, function.registerCount());
            writeByte(out, function.params().size());
            writeByte(out, function.captures().size());
            writeInt(out, bodyOffset);
            writeInt(out, function.code().length);
            for (String param : function.params()) {
                writeShort(out, indexOf(bytecode, param));
            }
            for (String capture : function.captures()) {
                writeShort(out, indexOf(bytecode, capture));
            }
            bodyOffset += function.code().length;
        }

        writeShort(out, bytecode.rules().size());
        for (RuleEntry rule : bytecode.rules()) {
            writeShort(out, indexOf(bytecode, rule.name()));
            writeShort(out, rule.functionIndex());
        }
        for (BytecodeFunction function : functions) {
            out.writeBytes(function.code());
        }
        return out.toByteArray();
    }

    private static byte[] writeMetadata(PrlBytecode bytecode) {
        List<Object> rules = new ArrayList<>(bytecode.rules().size());
        for (RuleEntry rule : bytecode.rules()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", rule.name());
            entry.putAll(rule.metadata());
            rules.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("prl_format", PrlBytecode.FORMAT_VERSION);
        root.put("rules", rules);
        return Json.write(root).getBytes(StandardCharsets.UTF_8);
    }

    private static int indexOf(PrlBytecode bytecode, String text) {
        int index = bytecode.strings().indexOf(text);
        if (index < 0) {
            throw new PrlException("字符串表里缺少 '" + text + "'");
        }
        return index;
    }

    // ------------------------------------------------------------------ 读

    public static PrlBytecode read(byte[] file) {
        if (file.length < HEADER_SIZE) {
            throw new PrlException("字节码文件长度不足 " + HEADER_SIZE + " 字节，不是合法的 .prlc");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (file[i] != MAGIC[i]) {
                throw new PrlException(".prlc 文件头不是 PRLC");
            }
        }
        int version = readShort(file, 4);
        if (version != PrlBytecode.FORMAT_VERSION) {
            throw new PrlException("不支持的字节码格式版本 " + version);
        }
        // 六个数从第 9 字节开始：Magic(4) + Version(2) + Flags(1) + Rule Count(2) 之后，
        // 与 write() 的布局一一对应（原来的 8 起读少算了一个 Flags 字节）。
        int stringOffset = readInt(file, 9);
        int stringSize = readInt(file, 13);
        int codeOffset = readInt(file, 17);
        int codeSize = readInt(file, 21);
        int metadataOffset = readInt(file, 25);
        int metadataSize = readInt(file, 29);

        List<String> strings = readStringTable(file, stringOffset, stringSize);
        List<Map<String, Object>> metadata = readMetadata(file, metadataOffset, metadataSize);
        return readCodeSection(file, codeOffset, codeSize, strings, metadata);
    }

    private static List<String> readStringTable(byte[] file, int offset, int size) {
        checkBounds(file, offset, size, "字符串表");
        List<String> strings = new ArrayList<>();
        int start = offset;
        int end = offset + size;
        for (int i = start; i < end; i++) {
            if (file[i] == 0) {
                strings.add(new String(file, start, i - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        return strings;
    }

    private static List<Map<String, Object>> readMetadata(byte[] file, int offset, int size) {
        checkBounds(file, offset, size, "元数据段");
        if (size == 0) {
            return List.of();
        }
        Map<String, Object> root = Json.readObject(new String(file, offset, size, StandardCharsets.UTF_8));
        List<Map<String, Object>> rules = new ArrayList<>();
        Object raw = root.get("rules");
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> pair : map.entrySet()) {
                        entry.put(String.valueOf(pair.getKey()), pair.getValue());
                    }
                    rules.add(entry);
                }
            }
        }
        return rules;
    }

    private static PrlBytecode readCodeSection(byte[] file, int offset, int size, List<String> strings,
                                              List<Map<String, Object>> metadata) {
        checkBounds(file, offset, size, "代码段");
        Cursor cursor = new Cursor(file, offset, offset + size);
        int functionCount = cursor.readShort();
        String[] names = new String[functionCount];
        boolean[] entries = new boolean[functionCount];
        int[] registers = new int[functionCount];
        List<List<String>> params = new ArrayList<>(functionCount);
        List<List<String>> captures = new ArrayList<>(functionCount);
        int[] codeOffsets = new int[functionCount];
        int[] codeSizes = new int[functionCount];

        for (int i = 0; i < functionCount; i++) {
            names[i] = strings.get(cursor.readShort());
            entries[i] = (cursor.readByte() & FLAG_ENTRY) != 0;
            registers[i] = cursor.readByte();
            int paramCount = cursor.readByte();
            int captureCount = cursor.readByte();
            codeOffsets[i] = cursor.readInt();
            codeSizes[i] = cursor.readInt();
            List<String> functionParams = new ArrayList<>(paramCount);
            for (int p = 0; p < paramCount; p++) {
                functionParams.add(strings.get(cursor.readShort()));
            }
            List<String> functionCaptures = new ArrayList<>(captureCount);
            for (int c = 0; c < captureCount; c++) {
                functionCaptures.add(strings.get(cursor.readShort()));
            }
            params.add(functionParams);
            captures.add(functionCaptures);
        }

        int ruleCount = cursor.readShort();
        List<int[]> ruleIndex = new ArrayList<>(ruleCount);
        List<String> ruleNames = new ArrayList<>(ruleCount);
        for (int i = 0; i < ruleCount; i++) {
            ruleNames.add(strings.get(cursor.readShort()));
            ruleIndex.add(new int[]{cursor.readShort()});
        }

        List<BytecodeFunction> functions = new ArrayList<>(functionCount);
        for (int i = 0; i < functionCount; i++) {
            checkBounds(file, offset + codeOffsets[i], codeSizes[i], "函数 " + names[i]);
            byte[] code = new byte[codeSizes[i]];
            System.arraycopy(file, offset + codeOffsets[i], code, 0, codeSizes[i]);
            functions.add(new BytecodeFunction(names[i], params.get(i), captures.get(i), code,
                    registers[i], entries[i]));
        }

        List<RuleEntry> rules = new ArrayList<>(ruleCount);
        for (int i = 0; i < ruleCount; i++) {
            String name = ruleNames.get(i);
            Map<String, Object> entry = Map.of();
            for (Map<String, Object> candidate : metadata) {
                if (name.equals(candidate.get("name"))) {
                    entry = candidate;
                    break;
                }
            }
            rules.add(new RuleEntry(name, ruleIndex.get(i)[0], entry));
        }
        return new PrlBytecode(functions, rules, strings);
    }

    // ------------------------------------------------------------------ 字节工具

    private static void checkBounds(byte[] file, int offset, int size, String what) {
        if (offset < 0 || size < 0 || offset + size > file.length) {
            throw new PrlException(".prlc 的" + what + "越界（offset=" + offset + ", size=" + size + "）");
        }
    }

    private static void writeByte(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static int readShort(byte[] file, int offset) {
        return ((file[offset] & 0xFF) << 8) | (file[offset + 1] & 0xFF);
    }

    private static int readInt(byte[] file, int offset) {
        return ((file[offset] & 0xFF) << 24) | ((file[offset + 1] & 0xFF) << 16)
                | ((file[offset + 2] & 0xFF) << 8) | (file[offset + 3] & 0xFF);
    }

    private static final class Cursor {

        private final byte[] file;
        private int pos;
        private final int end;

        Cursor(byte[] file, int start, int end) {
            this.file = file;
            this.pos = start;
            this.end = end;
        }

        int readByte() {
            require(1);
            return file[pos++] & 0xFF;
        }

        int readShort() {
            require(2);
            int value = ((file[pos] & 0xFF) << 8) | (file[pos + 1] & 0xFF);
            pos += 2;
            return value;
        }

        int readInt() {
            require(4);
            int value = ((file[pos] & 0xFF) << 24) | ((file[pos + 1] & 0xFF) << 16)
                    | ((file[pos + 2] & 0xFF) << 8) | (file[pos + 3] & 0xFF);
            pos += 4;
            return value;
        }

        private void require(int count) {
            if (pos + count > end) {
                throw new PrlException("代码段意外结束");
            }
        }
    }
}