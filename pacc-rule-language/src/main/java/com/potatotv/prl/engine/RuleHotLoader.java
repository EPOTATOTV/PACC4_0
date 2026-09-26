package com.potatotv.prl.engine;

import com.potatotv.prl.compiler.CompileResult;
import com.potatotv.prl.compiler.PrlCompiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 规则文件热加载（设计文档 §2.12.1）。
 *
 * <p>看守一个目录，看到 {@code *.prl} 的增删改就重新编译并原子替换。编译失败<strong>不卸载旧规则</strong>，
 * 错误记进 {@link #errors()} 并通过宿主的 {@code log} 报出去 —— 规则文件写坏的瞬间正是最需要旧规则
 * 还活着的时候（§2.12.1 的「编译失败 → 记录错误，保留旧规则，告警」）。</p>
 *
 * <p>去重靠内容指纹而不是事件类型：编辑器保存一个文件常常连发两三个 {@code ENTRY_MODIFY}，
 * 内容没变就没有必要重新编译。指纹是文件内容的 SHA-256，和 §2.13.1 版本表里的 {@code checksum}
 * 用同一个算法，两处对同一条规则的校验结果一致。</p>
 *
 * <p>轮询间隔 200ms，满足 §2.12.1「新规则生效 &lt;1s」。线程是守护线程，不会拖住宿主退出；
 * 但仍然要实现 {@link AutoCloseable}，让宿主能在测试或重配时确定性地停掉它。</p>
 */
public final class RuleHotLoader implements AutoCloseable {

    private static final String EXTENSION = ".prl";

    private static final long POLL_MILLIS = 200L;

    private final Path directory;
    private final RuleManager manager;
    private final PrlCompiler compiler;
    private final WatchService watchService;

    /** 规则名 → 上次装载时的内容指纹；用来跳过重复事件。 */
    private final Map<String, String> fingerprints = new ConcurrentHashMap<>();

    /** 规则名 → 最近一次编译失败的描述；编译成功后移除。 */
    private final Map<String, String> errors = new ConcurrentHashMap<>();

    private volatile Thread thread;
    private volatile boolean running;

    public RuleHotLoader(Path directory, RuleManager manager) throws IOException {
        this.directory = directory;
        this.manager = manager;
        this.compiler = new PrlCompiler(manager.host());
        this.watchService = directory.getFileSystem().newWatchService();
    }

    // ------------------------------------------------------------------ 生命周期

    /** 装载目录里已有的规则，然后开始监听变更。重复调用无副作用。 */
    public void start() throws IOException {
        if (running) {
            return;
        }
        running = true;
        for (Path file : ruleFiles()) {
            reload(file);
        }
        directory.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        Thread worker = new Thread(this::loop, "prl-hot-loader");
        worker.setDaemon(true);
        thread = worker;
        worker.start();
    }

    @Override
    public void close() {
        running = false;
        try {
            watchService.close();
        } catch (IOException e) {
            // 关不掉没有补救办法，也不影响后续流程：线程看到 running=false 就会退出。
        }
        Thread worker = thread;
        if (worker != null) {
            try {
                worker.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ 查询

    /** 当前编译失败的规则名与错误描述。 */
    public Map<String, String> errors() {
        return Map.copyOf(errors);
    }

    /** 已装载的规则名，字典序。 */
    public List<String> loadedRules() {
        return List.copyOf(new TreeSet<>(fingerprints.keySet()));
    }

    public Path directory() {
        return directory;
    }

    // ------------------------------------------------------------------ 内部

    private void loop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }
            if (key == null) {
                continue;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    reloadAll();
                    continue;
                }
                Object context = event.context();
                if (context instanceof Path relative) {
                    handle(relative);
                }
            }
            if (!key.reset()) {
                return;
            }
        }
    }

    private void handle(Path relative) {
        Path file = directory.resolve(relative);
        String name = ruleName(file);
        if (name == null) {
            return;
        }
        if (Files.notExists(file)) {
            manager.unloadRule(name);
            fingerprints.remove(name);
            errors.remove(name);
            return;
        }
        reload(file);
    }

    /** 重新扫描目录，全量对齐一次。目录里的 {@code *.prl} 之外的文件不动。 */
    public void reloadAll() {
        for (Path file : ruleFiles()) {
            reload(file);
        }
    }

    private void reload(Path file) {
        String name = ruleName(file);
        if (name == null) {
            return;
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail(name, "读取失败：" + e.getMessage());
            return;
        }
        String fingerprint = Checksums.sha256(content);
        if (fingerprint.equals(fingerprints.get(name))) {
            return;
        }
        CompileResult result = compiler.compileChecked(content);
        if (!result.ok()) {
            fail(name, result.describe());
            return;
        }
        manager.loadBytecode(name, result.bytecode());
        fingerprints.put(name, fingerprint);
        errors.remove(name);
        if (!result.warnings().isEmpty()) {
            manager.host().log("warn", "规则 " + name + " 有警告：" + result.describe());
        }
    }

    private void fail(String name, String message) {
        errors.put(name, message);
        manager.host().log("error", "规则 " + name + " 编译失败，保留旧版本：" + message);
    }

    private List<Path> ruleFiles() {
        if (Files.notExists(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> files = new ArrayList<>();
            stream.filter(path -> ruleName(path) != null).sorted().forEach(files::add);
            return files;
        } catch (IOException e) {
            manager.host().log("error", "扫描规则目录失败：" + e.getMessage());
            return List.of();
        }
    }

    /** 文件名（去掉 {@code .prl}）即规则名；不是规则文件返回 {@code null}。 */
    private static String ruleName(Path path) {
        String fileName = path.getFileName().toString();
        if (!fileName.endsWith(EXTENSION) || fileName.length() == EXTENSION.length()) {
            return null;
        }
        return fileName.substring(0, fileName.length() - EXTENSION.length());
    }
}