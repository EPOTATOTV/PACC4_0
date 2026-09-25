package com.potatotv.paccclient.ai;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 端侧模型仓库：模型文件的安装 / 读取 / 回滚 / 热加载（文档 §2.1.3）。
 *
 * <p>存储目录沿用客户端约定（{@code %APPDATA%\PACC} / {@code ~/Library/Application Support/PACC} /
 * {@code ~/.config/pacc}），可用环境变量 {@code PACC_MODEL_DIR} 覆盖。安装时先做容器
 * SHA-256 完整性与可选签名校验，再以「临时文件 + {@code ATOMIC_MOVE}」原子替换，
 * 同时把旧文件留存为 {@code .bak} 以便一键回滚。</p>
 */
public final class ModelRepository {

    private static final String FILE_XGB = "model-xgb.paccm";
    private static final String FILE_AE = "model-ae.paccm";
    private static final String FILE_LSTM = "model-lstm.paccm";
    private static final String BAK_SUFFIX = ".bak";

    private final Path dir;

    public ModelRepository() {
        this(resolveDir());
    }

    public ModelRepository(Path dir) {
        this.dir = dir;
    }

    /** 解析模型目录：环境变量 {@code PACC_MODEL_DIR} 优先，否则按平台约定（与客户端一致）。 */
    public static Path resolveDir() {
        String env = System.getenv("PACC_MODEL_DIR");
        if (env != null && !env.isBlank()) return Path.of(env);
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            String base = (appdata != null && !appdata.isBlank()) ? appdata : System.getProperty("user.home");
            return Path.of(base, "PACC");
        }
        if (os.contains("mac")) {
            return Path.of(System.getProperty("user.home"), "Library", "Application Support", "PACC");
        }
        return Path.of(System.getProperty("user.home"), ".config", "pacc");
    }

    public Path dir() {
        return dir;
    }

    /** 模型类型 → 文件路径。 */
    public Path modelFile(int modelType) {
        return switch (modelType) {
            case PaccModelFormat.TYPE_XGBOOST -> dir.resolve(FILE_XGB);
            case PaccModelFormat.TYPE_AUTOENCODER -> dir.resolve(FILE_AE);
            case PaccModelFormat.TYPE_LSTM_AE -> dir.resolve(FILE_LSTM);
            default -> throw new IllegalArgumentException("未知模型类型: " + modelType);
        };
    }

    /**
     * 模型类型 → 签名文件路径（{@code <模型文件>.sig}）。
     * <p>下载链路（{@link ModelSync}）在安装前把服务端签名写入该文件，安装时按需校验。</p>
     */
    public Path signatureFile(int modelType) {
        return sigFile(modelFile(modelType));
    }

    /**
     * 安装模型：校验容器完整性与可选签名后原子落盘，旧文件转存 {@code .bak}。
     *
     * @return 安装成功返回 {@code true}；校验失败、签名缺失/不合法或 IO 异常返回 {@code false}
     */
    public boolean install(byte[] raw, PaccModelFormat.ModelHeader h) {
        if (raw == null || h == null) return false;
        try {
            // 1) 容器完整性：魔数 + 长度一致性 + SHA-256
            PaccModelFormat.ReadModel rm = PaccModelFormat.read(raw);
            if (rm.header().modelType() != h.modelType() || rm.header().featureDim() != h.featureDim()) {
                return false;
            }
            Path target = modelFile(h.modelType());
            // 2) 可选签名：配置了公钥则必须有合法签名
            if (ModelSignatureVerifier.configured()) {
                Path sigFile = sigFile(target);
                if (!Files.exists(sigFile)) return false;
                if (!ModelSignatureVerifier.verify(raw, Files.readAllBytes(sigFile))) return false;
            }
            // 3) 原子替换，旧文件留作回滚
            Files.createDirectories(dir);
            if (Files.exists(target)) {
                Files.copy(target, bakFile(target), StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, raw);
            atomicMove(tmp, target);
            return true;
        } catch (ModelFormatException e) {
            System.err.println("[PTV-Client] 模型校验失败: " + e.getMessage());
            return false;
        } catch (IOException e) {
            System.err.println("[PTV-Client] 模型安装失败: " + e.getMessage());
            return false;
        }
    }

    /** 读取当前模型字节；不存在或读取失败返回 {@code null}。 */
    public byte[] loadCurrent(int modelType) {
        try {
            Path f = modelFile(modelType);
            return Files.exists(f) ? Files.readAllBytes(f) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 回滚到上一次安装的备份；无备份返回 {@code false}。 */
    public boolean rollback(int modelType) {
        try {
            Path target = modelFile(modelType);
            Path bak = bakFile(target);
            if (!Files.exists(bak)) return false;
            Files.createDirectories(dir);
            Files.copy(bak, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[PTV-Client] 模型回滚失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 读取当前各类模型文件并加载进目标 {@link LocalAiModel}（热更新入口）。
     * <p>单个类型加载失败仅跳过该类型，不影响目标已有的其他模型（保持上一次状态）。</p>
     */
    public LocalAiModel loadInto(LocalAiModel target) {
        for (int type : new int[]{PaccModelFormat.TYPE_XGBOOST, PaccModelFormat.TYPE_AUTOENCODER, PaccModelFormat.TYPE_LSTM_AE}) {
            byte[] raw = loadCurrent(type);
            if (raw == null) continue;
            try {
                target.load(raw);
            } catch (IOException | RuntimeException e) {
                System.err.println("[PTV-Client] 模型热加载失败(类型 " + type + "): " + e.getMessage());
            }
        }
        return target;
    }

    private static Path sigFile(Path model) {
        return model.resolveSibling(model.getFileName() + ".sig");
    }

    private static Path bakFile(Path model) {
        return model.resolveSibling(model.getFileName() + BAK_SUFFIX);
    }

    private static void atomicMove(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}