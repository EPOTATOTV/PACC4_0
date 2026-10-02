package com.potatotv.pob;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 控制流平坦化（保守子集）：把方法体切成基本块，重组成 {@code switch(state)} 状态机，
 * 块与块之间的直接跳转改成「写 state + 回跳分发器」。
 *
 * <h2>为什么必须生成 StackMapTable</h2>
 * <p>class 版本 ≥ 50 时，每个跳转目标都必须有 StackMapTable frame。平坦化把每个基本块入口
 * 都变成 {@code tableswitch} 的目标，也就是凭空多出一批跳转目标，因此必须<b>新建</b>整张
 * StackMapTable。基线里 {@link CodeRewriter} 只会「重映射已有 frame」，不会生成新 frame，
 * 平坦化是第一个必须自己产出 frame 的变换。</p>
 *
 * <h2>保守边界（改动前务必先读）</h2>
 * <p>生成 frame 需要「每个基本块入口的局部变量类型」。完整做法是照抄 JVM 验证器做类型数据流
 * 分析（含引用类型的公共父类合并），工作量和出错面都很大；一旦算错就是 VerifyError，
 * 产物直接加载失败，代价远大于收益。所以这里只处理一个<b>可证明安全</b>的子集：</p>
 * <ul>
 *   <li>无异常表（try/catch 跨越状态机边界需要重建异常表语义，不在本次范围）；</li>
 *   <li>无 {@code jsr/ret}（子程序返回地址无法进状态机）、无 {@code wide}、无
 *       {@code goto_w/jsr_w}、无 {@code tableswitch/lookupswitch}（原生 switch 会和状态机
 *       的分发器缠在一起）；</li>
 *   <li>原 StackMapTable 里所有 frame 的 locals <b>完全一致</b>、stack 为空，且不含
 *       UninitializedThis / Uninitialized（未初始化对象不能跨越状态机分发点）；</li>
 *   <li>每个基本块入口要么本来就有 frame，要么是某个「不含 store 指令」的条件分支的
 *       fall-through（此时入口 locals 必然等于它的入口 frame，locas 不变，可安全地套用公共
 *       locals）；否则跳过；</li>
 *   <li>偏移 0 的基本块不能是跳转目标（入口块内联在分发器之前，不作为一个 case）；</li>
 *   <li>构造器 {@code <init>} 一律跳过（{@code this} 处于未初始化状态，类型特殊）。</li>
 * </ul>
 * <p>不满足上述任一条的方法原样放行。上面这些约束合起来保证了：每个块出口状态都可以安全地
 * 声明成那个公共 locals（原验证器本来就接受「块出口 → 后继块入口 frame = 公共 locals」），
 * 于是整张新 StackMapTable 只用一种 frame（full_frame：公共 locals + 空 stack）即可。</p>
 *
 * <p>默认 {@code flatten = false}，且即便打开也是逐方法判定，无法处理的方法只告警不动。</p>
 */
final class ControlFlowFlattener {

    /** 指令太少的方法平坦化只会变大变慢，没有收益。 */
    private static final int MIN_INSTRUCTIONS = 8;

    /** 状态数上限（同时受 {@code istore} 单字节下标约束）。 */
    private static final int MAX_CASES = 256;

    private ControlFlowFlattener() {
    }

    /**
     * 尝试对类的每个方法做平坦化。
     *
     * @return 实际被平坦化的方法数（无法安全处理的方法原样放行）
     */
    static int apply(ClassFile cf) {
        int count = 0;
        for (ClassFile.Member method : cf.methods()) {
            if (method.attributes.isEmpty()) {
                continue; // abstract / native：没有 Code 属性
            }
            if ("<init>".equals(cf.utf8(method.nameIndex))) {
                continue; // 构造器的 this 处于未初始化状态，类型特殊，跳过
            }
            for (ClassFile.Attr attr : method.attributes) {
                if (!"Code".equals(cf.utf8(attr.nameIndex))) {
                    continue;
                }
                try {
                    byte[] rewritten = new Job(cf, method, attr.info).run();
                    if (rewritten != null) {
                        attr.info = rewritten;
                        count++;
                    }
                } catch (RuntimeException e) {
                    System.err.println("POB：跳过无法平坦化的方法：" + cf.thisName() + "."
                            + cf.utf8(method.nameIndex) + "：" + e.getMessage());
                }
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 单方法
    // ------------------------------------------------------------------

    /** 一段基本块：指令下标 [start, end)。 */
    private static final class Block {
        final int start;
        final int end;

        Block(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /** 一个已解析的 frame：locals 项列表（tag + 可选下标）与 stack 项列表。 */
    private static final class Frame {
        final List<int[]> locals;
        final List<int[]> stack;

        Frame(List<int[]> locals, List<int[]> stack) {
            this.locals = locals;
            this.stack = stack;
        }
    }

    private static final class Job {

        private final ClassFile cf;
        private final ClassFile.Member method;
        private final byte[] info;

        private int maxStack;
        private int maxLocals;
        private byte[] code;
        private List<Bytecode.Insn> insns;
        private final Map<Integer, Integer> indexOfOffset = new HashMap<>();
        private final Set<Integer> branchTargets = new HashSet<>();
        private List<Block> blocks;
        private int[] blockOf;
        private final Map<Integer, Frame> offsetToFrame = new HashMap<>();
        private List<int[]> commonLocals;

        Job(ClassFile cf, ClassFile.Member method, byte[] info) {
            this.cf = cf;
            this.method = method;
            this.info = info;
        }

        byte[] run() {
            if (!parse()) {
                return null;
            }
            return emit();
        }

        // --------------------------------------------------------------
        // 解析与校验
        // --------------------------------------------------------------

        private boolean parse() {
            Cursor c = new Cursor(info);
            maxStack = c.u2();
            maxLocals = c.u2();
            int codeLength = (int) c.u4();
            if (codeLength <= 0) {
                return false;
            }
            code = c.bytes(codeLength);
            int exceptionCount = c.u2();
            c.bytes(exceptionCount * 8);
            if (exceptionCount != 0) {
                return false; // 带异常表的方法不进状态机
            }

            byte[] stackMap = null;
            int subCount = c.u2();
            for (int i = 0; i < subCount; i++) {
                int nameIndex = c.u2();
                int length = (int) c.u4();
                byte[] body = c.bytes(length);
                // 只认识 StackMapTable；类型注解等偏移类子属性会让重建变得不安全，直接放弃
                if (!"StackMapTable".equals(cf.utf8(nameIndex)) || stackMap != null) {
                    return false;
                }
                stackMap = body;
            }
            if (stackMap == null) {
                return false;
            }
            if (maxLocals > 255) {
                return false; // 状态变量追加在 maxLocals 槽，istore 单字节下标要求 ≤ 255
            }

            insns = Bytecode.scan(code);
            if (insns.size() < MIN_INSTRUCTIONS) {
                return false;
            }
            if (!scanOpcodes()) {
                return false;
            }
            if (!buildBlocks()) {
                return false;
            }
            if (blocks.size() < 2 || blocks.size() > MAX_CASES) {
                return false;
            }
            if (!reachable()) {
                return false;
            }
            if (!parseFrames(stackMap)) {
                return false;
            }
            return blocksFramed();
        }

        /** 拒绝一切无法安全进入状态机的操作码。 */
        private boolean scanOpcodes() {
            for (Bytecode.Insn insn : insns) {
                int op = insn.opcode;
                if (op == 0xa8 || op == 0xa9 // jsr / ret
                        || op == Bytecode.WIDE
                        || op == Bytecode.GOTO_W || op == Bytecode.JSR_W
                        || op == Bytecode.TABLESWITCH || op == Bytecode.LOOKUPSWITCH) {
                    return false;
                }
            }
            return true;
        }

        private boolean buildBlocks() {
            for (int i = 0; i < insns.size(); i++) {
                indexOfOffset.put(insns.get(i).pos, i);
            }
            boolean[] leader = new boolean[insns.size()];
            leader[0] = true;
            for (int i = 0; i < insns.size(); i++) {
                Bytecode.Insn insn = insns.get(i);
                if (isBranch(insn.opcode)) {
                    Integer target = indexOfOffset.get(targetOffset(insn));
                    if (target == null) {
                        return false; // 跳到方法体外：不合法
                    }
                    branchTargets.add(targetOffset(insn));
                    leader[target] = true;
                    if (i + 1 < insns.size()) {
                        leader[i + 1] = true;
                    }
                } else if (isReturnOrThrow(insn.opcode) && i + 1 < insns.size()) {
                    leader[i + 1] = true;
                }
            }
            if (branchTargets.contains(0)) {
                return false; // 入口块必须是内联块，不能被跳转进来
            }
            Bytecode.Insn last = insns.get(insns.size() - 1);
            if (!isReturnOrThrow(last.opcode) && last.opcode != 0xa7) {
                return false; // 方法体必须以 return / athrow / goto 收尾
            }

            blocks = new ArrayList<>();
            int start = -1;
            for (int i = 0; i < insns.size(); i++) {
                if (leader[i]) {
                    if (start >= 0) {
                        blocks.add(new Block(start, i));
                    }
                    start = i;
                }
            }
            blocks.add(new Block(start, insns.size()));
            blockOf = new int[insns.size()];
            for (int b = 0; b < blocks.size(); b++) {
                for (int i = blocks.get(b).start; i < blocks.get(b).end; i++) {
                    blockOf[i] = b;
                }
            }
            return true;
        }

        /** 分支目标偏移；被接受的分支都是 2 字节相对偏移（goto_w / jsr_w 已在 scanOpcodes 拒绝）。 */
        private int targetOffset(Bytecode.Insn insn) {
            return insn.pos + Bytecode.readShort(code, insn.pos + 1);
        }

        private boolean reachable() {
            boolean[] seen = new boolean[blocks.size()];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            seen[0] = true;
            queue.add(0);
            while (!queue.isEmpty()) {
                int b = queue.poll();
                for (int s : successors(b)) {
                    if (!seen[s]) {
                        seen[s] = true;
                        queue.add(s);
                    }
                }
            }
            for (boolean s : seen) {
                if (!s) {
                    return false; // 不可达基本块：栈状态无从谈起
                }
            }
            return true;
        }

        /** 基本块后继块下标；数组为空表示 ret/throw。 */
        private int[] successors(int b) {
            Block block = blocks.get(b);
            Bytecode.Insn last = insns.get(block.end - 1);
            int op = last.opcode;
            if (isReturnOrThrow(op)) {
                return new int[0];
            }
            if (op == 0xa7) {
                return new int[]{blockOf[indexOfOffset.get(targetOffset(last))]};
            }
            if (isConditional(op)) {
                int target = blockOf[indexOfOffset.get(targetOffset(last))];
                return new int[]{target, b + 1};
            }
            return new int[]{b + 1};
        }

        /** 解析 StackMapTable，落出「偏移 -> frame」，并确定公共 locals。 */
        private boolean parseFrames(byte[] stackMap) {
            List<int[]> current = initialLocals();
            Cursor c = new Cursor(stackMap);
            int count = c.u2();
            int prev = -1;
            for (int i = 0; i < count; i++) {
                int type = c.u1();
                int absolute;
                List<int[]> stack = new ArrayList<>();
                if (type <= 63) {
                    absolute = prev + 1 + type;
                } else if (type <= 127) {
                    absolute = prev + 1 + (type - 64);
                    stack.add(readItem(c));
                } else if (type == 247) {
                    absolute = prev + 1 + c.u2();
                    stack.add(readItem(c));
                } else if (type >= 248 && type <= 250) {
                    absolute = prev + 1 + c.u2();
                    int k = 251 - type;
                    if (k > current.size()) {
                        return false;
                    }
                    current.subList(current.size() - k, current.size()).clear();
                } else if (type == 251) {
                    absolute = prev + 1 + c.u2();
                } else if (type >= 252 && type <= 254) {
                    absolute = prev + 1 + c.u2();
                    int k = type - 251;
                    for (int j = 0; j < k; j++) {
                        current.add(readItem(c));
                    }
                } else if (type == 255) {
                    absolute = prev + 1 + c.u2();
                    int nLocals = c.u2();
                    List<int[]> locals = new ArrayList<>(nLocals);
                    for (int j = 0; j < nLocals; j++) {
                        locals.add(readItem(c));
                    }
                    current = locals;
                    int nStack = c.u2();
                    for (int j = 0; j < nStack; j++) {
                        stack.add(readItem(c));
                    }
                } else {
                    return false;
                }
                offsetToFrame.put(absolute, new Frame(copy(current), stack));
                prev = absolute;
            }

            for (Frame frame : offsetToFrame.values()) {
                if (!frame.stack.isEmpty()) {
                    return false; // 分发点要求块入口空栈
                }
                for (int[] item : frame.locals) {
                    if (item[0] == 6 || item[0] == 8) {
                        return false; // 未初始化类型不能进状态机
                    }
                }
            }
            if (offsetToFrame.isEmpty()) {
                return false;
            }
            commonLocals = offsetToFrame.values().iterator().next().locals;
            if (localSlots(commonLocals) != maxLocals) {
                return false; // 公共 locals 必须覆盖全部槽位，避免隐式 top 造成类型放宽
            }
            for (Frame frame : offsetToFrame.values()) {
                if (!sameItems(frame.locals, commonLocals)) {
                    return false;
                }
            }
            return true;
        }

        /** 每个块入口都必须能确定 locals：有 frame，或是无 store 的条件分支 fall-through。 */
        private boolean blocksFramed() {
            for (int b = 1; b < blocks.size(); b++) {
                int startIndex = blocks.get(b).start;
                int startOffset = insns.get(startIndex).pos;
                if (offsetToFrame.containsKey(startOffset)) {
                    continue;
                }
                if (branchTargets.contains(startOffset)) {
                    return false; // 跳转目标却没有 frame：原 class 不合规，放弃
                }
                if (startIndex == 0) {
                    return false;
                }
                Bytecode.Insn previous = insns.get(startIndex - 1);
                if (!isConditional(previous.opcode)) {
                    return false;
                }
                int previousBlock = blockOf[startIndex - 1];
                if (blocks.get(previousBlock).end != startIndex) {
                    return false;
                }
                for (int j = blocks.get(previousBlock).start; j < blocks.get(previousBlock).end; j++) {
                    if (isStore(insns.get(j).opcode)) {
                        return false; // 前驱改了局部变量类型，fall-through 入口 locals 不再等于公共 locals
                    }
                }
            }
            return true;
        }

        /** 从方法描述符推导入口帧 locals（验证器的隐式初始帧）。 */
        private List<int[]> initialLocals() {
            boolean isStatic = (method.access & ClassFile.ACC_STATIC) != 0;
            List<int[]> out = new ArrayList<>();
            if (!isStatic) {
                out.add(new int[]{7, cf.addClass(cf.thisName())});
            }
            String desc = cf.utf8(method.descriptorIndex);
            int i = desc.indexOf('(') + 1;
            while (i < desc.length() && desc.charAt(i) != ')') {
                char ch = desc.charAt(i);
                switch (ch) {
                    case 'B', 'C', 'I', 'S', 'Z' -> {
                        out.add(new int[]{1, 0});
                        i++;
                    }
                    case 'F' -> {
                        out.add(new int[]{2, 0});
                        i++;
                    }
                    case 'J' -> {
                        out.add(new int[]{4, 0});
                        i++;
                    }
                    case 'D' -> {
                        out.add(new int[]{3, 0});
                        i++;
                    }
                    case 'L' -> {
                        int end = desc.indexOf(';', i);
                        out.add(new int[]{7, cf.addClass(desc.substring(i + 1, end))});
                        i = end + 1;
                    }
                    case '[' -> {
                        int start = i;
                        while (desc.charAt(i) == '[') {
                            i++;
                        }
                        if (desc.charAt(i) == 'L') {
                            i = desc.indexOf(';', i) + 1;
                        } else {
                            i++;
                        }
                        out.add(new int[]{7, cf.addClass(desc.substring(start, i))});
                    }
                    default -> throw new IllegalArgumentException("非法方法描述符：" + desc);
                }
            }
            return out;
        }

        // --------------------------------------------------------------
        // 发射
        // --------------------------------------------------------------

        private byte[] emit() {
            int stateSlot = maxLocals;
            Emitter e = new Emitter();
            int[] caseLabel = new int[blocks.size()];
            List<Integer> frameLabels = new ArrayList<>();
            int dispatch = e.newLabel();
            frameLabels.add(dispatch);
            for (int i = 1; i < blocks.size(); i++) {
                caseLabel[i] = e.newLabel();
                frameLabels.add(caseLabel[i]);
            }

            // 先把 state 槽写成 0，让它在整个方法体里恒为 int：否则内联 prologue 里的
            // 条件分支会在 state 尚未赋值时跳到 stub，那一处 locals[state] 还是 top，
            // 与帧里声明的 int 冲突。偏移 0 永远不会是跳转目标（buildBlocks 已拒绝），
            // 所以这里不需要额外的 frame。
            e.u1(0x03);              // iconst_0
            e.u1(0x36);              // istore
            e.u1(stateSlot);

            emitBlock(e, 0, dispatch, stateSlot, frameLabels);

            e.mark(dispatch);
            e.u1(0x15);              // iload
            e.u1(stateSlot);
            int switchPos = e.pos();
            e.u1(Bytecode.TABLESWITCH);
            int pad = (4 - ((switchPos + 1) & 3)) & 3;
            for (int i = 0; i < pad; i++) {
                e.u1(0);
            }
            int low = 1;
            int high = blocks.size() - 1;
            int defaultPos = e.pos();
            e.u4(0);
            e.patchSwitch(caseLabel[low], switchPos, defaultPos);
            e.u4(low);
            e.u4(high);
            for (int s = low; s <= high; s++) {
                int pos = e.pos();
                e.u4(0);
                e.patchSwitch(caseLabel[s], switchPos, pos);
            }

            for (int i = 1; i < blocks.size(); i++) {
                e.mark(caseLabel[i]);
                emitBlock(e, i, dispatch, stateSlot, frameLabels);
            }

            byte[] newCode = e.finish();
            if (newCode.length > 0xFFFF) {
                return null; // 方法体超 65535：放弃，别产出非法 class
            }

            // 收集所有需要 frame 的偏移（分发器 / case 入口 / 条件分支 stub）
            TreeSet<Integer> frameOffsets = new TreeSet<>();
            for (int label : frameLabels) {
                frameOffsets.add(e.offsetOf(label));
            }
            byte[] stackMap = buildStackMap(frameOffsets);

            ByteArrayOutputStream out = new ByteArrayOutputStream(newCode.length + stackMap.length + 32);
            writeU2(out, maxStack + 1);      // 分发点额外压入 1 个 int state
            writeU2(out, maxLocals + 1);     // 追加 state 槽
            writeU4(out, newCode.length);
            out.write(newCode, 0, newCode.length);
            writeU2(out, 0);                 // 异常表为空
            writeU2(out, 1);                 // 只有一个子属性：StackMapTable
            writeU2(out, cf.utf8Index("StackMapTable"));
            writeU4(out, stackMap.length);
            out.write(stackMap, 0, stackMap.length);
            return out.toByteArray();
        }

        private byte[] buildStackMap(TreeSet<Integer> offsets) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(offsets.size() * 16 + 2);
            writeU2(out, offsets.size());
            int prev = -1;
            for (int offset : offsets) {
                int delta = offset - prev - 1;
                out.write(255);              // full_frame
                writeU2(out, delta);
                // 公共 locals 之后必须补上 state 槽：分发器的 iload 要求它是 int。
                // 每个 frame 落点（分发器、case 入口、条件分支 stub）之前都已写过 state，
                // 因此这里恒为 int，不需要额外的类型合并。
                writeU2(out, commonLocals.size() + 1);
                for (int[] item : commonLocals) {
                    out.write(item[0]);
                    if (item[0] == 7) {
                        writeU2(out, item[1]);
                    }
                }
                out.write(1);                // state 槽：int（tag 1）
                writeU2(out, 0);             // stack 为空
                prev = offset;
            }
            return out.toByteArray();
        }

        /** 发射一个基本块：顺序指令原样复制，终结指令改写成「写 state + 回跳」。 */
        private void emitBlock(Emitter e, int b, int dispatch, int stateSlot, List<Integer> frameLabels) {
            Block block = blocks.get(b);
            for (int i = block.start; i < block.end; i++) {
                Bytecode.Insn insn = insns.get(i);
                boolean last = i == block.end - 1;
                if (last && isReturnOrThrow(insn.opcode)) {
                    e.bytes(code, insn.pos, insn.length);
                    return;
                }
                if (last && insn.opcode == 0xa7) {
                    setState(e, blockOf[indexOfOffset.get(targetOffset(insn))], dispatch, stateSlot);
                    return;
                }
                if (last && isConditional(insn.opcode)) {
                    int target = blockOf[indexOfOffset.get(targetOffset(insn))];
                    int stub = e.newLabel();
                    frameLabels.add(stub);
                    int opcodePos = e.pos();
                    e.u1(insn.opcode);
                    e.u2(0);
                    e.patchShort(stub, opcodePos);
                    setState(e, b + 1, dispatch, stateSlot);
                    e.mark(stub);
                    setState(e, target, dispatch, stateSlot);
                    return;
                }
                e.bytes(code, insn.pos, insn.length);
            }
            // 没有终结指令：顺序落到下一块
            setState(e, b + 1, dispatch, stateSlot);
        }

        private void setState(Emitter e, int state, int dispatch, int stateSlot) {
            e.u1(0x11);              // sipush
            e.u2(state);
            e.u1(0x36);              // istore
            e.u1(stateSlot);
            int pos = e.pos();
            e.u1(0xc8);              // goto_w
            e.u4(0);
            e.patchWide(dispatch, pos);
        }
    }

    // ------------------------------------------------------------------
    // 指令分类
    // ------------------------------------------------------------------

    private static boolean isConditional(int op) {
        return (op >= 0x99 && op <= 0xa6) || op == 0xc6 || op == 0xc7;
    }

    private static boolean isBranch(int op) {
        return isConditional(op) || op == 0xa7;
    }

    private static boolean isReturnOrThrow(int op) {
        return (op >= 0xac && op <= 0xb1) || op == 0xbf;
    }

    private static boolean isStore(int op) {
        return op >= 0x36 && op <= 0x3a;
    }

    private static int[] readItem(Cursor c) {
        int tag = c.u1();
        if (tag == 7 || tag == 8) {
            return new int[]{tag, c.u2()};
        }
        return new int[]{tag, 0};
    }

    private static List<int[]> copy(List<int[]> items) {
        List<int[]> out = new ArrayList<>(items.size());
        for (int[] item : items) {
            out.add(item.clone());
        }
        return out;
    }

    private static boolean sameItems(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i)[0] != b.get(i)[0] || a.get(i)[1] != b.get(i)[1]) {
                return false;
            }
        }
        return true;
    }

    /** long / double 占两个局部变量槽。 */
    private static int localSlots(List<int[]> items) {
        int slots = 0;
        for (int[] item : items) {
            slots += item[0] == 3 || item[0] == 4 ? 2 : 1;
        }
        return slots;
    }

    // ------------------------------------------------------------------
    // 字节发射器（相对偏移回填）
    // ------------------------------------------------------------------

    private static final class Emitter {
        private byte[] buf = new byte[256];
        private int length;
        private final Map<Integer, Integer> marks = new HashMap<>();
        private final List<int[]> patches = new ArrayList<>();
        private int labelSeq;

        int newLabel() {
            return labelSeq++;
        }

        void mark(int label) {
            marks.put(label, length);
        }

        int offsetOf(int label) {
            Integer offset = marks.get(label);
            if (offset == null) {
                throw new IllegalStateException("标签未标记：" + label);
            }
            return offset;
        }

        int pos() {
            return length;
        }

        void u1(int value) {
            ensure(1);
            buf[length++] = (byte) value;
        }

        void u2(int value) {
            u1(value >>> 8);
            u1(value);
        }

        void u4(int value) {
            u1(value >>> 24);
            u1(value >>> 16);
            u1(value >>> 8);
            u1(value);
        }

        void bytes(byte[] source, int offset, int count) {
            ensure(count);
            System.arraycopy(source, offset, buf, length, count);
            length += count;
        }

        /** 2 字节相对分支：base 为操作码地址，补丁落在 base+1。 */
        void patchShort(int label, int opcodePos) {
            patches.add(new int[]{label, opcodePos, opcodePos + 1, 0});
        }

        /** 4 字节相对分支（goto_w）：base 为操作码地址，补丁落在 base+1。 */
        void patchWide(int label, int opcodePos) {
            patches.add(new int[]{label, opcodePos, opcodePos + 1, 1});
        }

        /** switch 的 4 字节相对偏移：base 为 switch 操作码地址。 */
        void patchSwitch(int label, int switchPos, int fieldPos) {
            patches.add(new int[]{label, switchPos, fieldPos, 1});
        }

        byte[] finish() {
            for (int[] patch : patches) {
                int value = offsetOf(patch[0]) - patch[1];
                if (patch[3] == 0) {
                    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                        throw new UnsupportedOperationException("短分支偏移越界");
                    }
                    buf[patch[2]] = (byte) (value >>> 8);
                    buf[patch[2] + 1] = (byte) value;
                } else {
                    buf[patch[2]] = (byte) (value >>> 24);
                    buf[patch[2] + 1] = (byte) (value >>> 16);
                    buf[patch[2] + 2] = (byte) (value >>> 8);
                    buf[patch[2] + 3] = (byte) value;
                }
            }
            return Arrays.copyOf(buf, length);
        }

        private void ensure(int count) {
            if (length + count > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, length + count));
            }
        }
    }

    // ------------------------------------------------------------------
    // 只读游标
    // ------------------------------------------------------------------

    private static final class Cursor {
        private final byte[] data;
        private int pos;

        Cursor(byte[] data) {
            this.data = data;
        }

        int u1() {
            check(1);
            return data[pos++] & 0xFF;
        }

        int u2() {
            check(2);
            int value = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            return value;
        }

        long u4() {
            check(4);
            long value = ((long) (data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
            return value;
        }

        byte[] bytes(int count) {
            check(count);
            byte[] out = Arrays.copyOfRange(data, pos, pos + count);
            pos += count;
            return out;
        }

        private void check(int count) {
            if (count < 0 || pos + count > data.length) {
                throw new IllegalArgumentException("属性被截断");
            }
        }
    }

    private static void writeU2(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeU4(ByteArrayOutputStream out, long value) {
        out.write((int) ((value >>> 24) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) (value & 0xFF));
    }
}