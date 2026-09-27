package com.potatotv.pcu;

import java.nio.file.Path;

/**
 * 一次更新尝试的结果。
 *
 * <p>区分 {@link Outcome#FAILED} 与 {@link Outcome#ROLLED_BACK}：前者是「旧版本还在、
 * 什么都没变」，后者是「新版本上来过、又被打回去了」——两者在服务端要触发的动作不一样。</p>
 */
public record UpdateResult(
        Outcome outcome,
        String fromVersion,
        String toVersion,
        boolean deltaApplied,
        boolean forceRequired,
        Path artifact,
        String message) {

    public enum Outcome {
        /** 服务端没有新版本。 */
        NO_UPDATE,
        /** 新版本已安装并启动。 */
        SUCCESS,
        /** 更新失败，旧版本仍可用。 */
        FAILED,
        /** 新版本启动异常，已回滚到旧版本。 */
        ROLLED_BACK,
        /** 本次跳过（静默更新失败、用户跳过等）。 */
        SKIPPED
    }

    /** 映射到 {@code /v1/update/report} 的状态字段；{@link Outcome#NO_UPDATE} 不上报。 */
    public UpdateStatus status() {
        return switch (outcome) {
            case SUCCESS -> UpdateStatus.SUCCESS;
            case FAILED -> UpdateStatus.FAILED;
            case ROLLED_BACK -> UpdateStatus.ROLLED_BACK;
            case SKIPPED, NO_UPDATE -> UpdateStatus.SKIPPED;
        };
    }

    public boolean succeeded() {
        return outcome == Outcome.SUCCESS;
    }

    /**
     * 服务端已是最新版本。
     *
     * <p>{@code toVersion} 留空：没有目标版本，把它填成当前版本会让调用方误以为
     * 「更新到了 5.4.0」。</p>
     */
    static UpdateResult noUpdate(String currentVersion) {
        return new UpdateResult(Outcome.NO_UPDATE, currentVersion, null,
                false, false, null, "已是最新版本");
    }

    static UpdateResult success(String from, String to, boolean deltaApplied, boolean force, Path artifact) {
        return new UpdateResult(Outcome.SUCCESS, from, to, deltaApplied, force, artifact,
                deltaApplied ? "差分更新完成" : "全量更新完成");
    }

    static UpdateResult failed(String from, String to, boolean deltaApplied, boolean force,
                               Path artifact, String message) {
        return new UpdateResult(Outcome.FAILED, from, to, deltaApplied, force, artifact, message);
    }

    static UpdateResult rolledBack(String from, String to, boolean deltaApplied, boolean force,
                                   Path artifact, String message) {
        return new UpdateResult(Outcome.ROLLED_BACK, from, to, deltaApplied, force, artifact, message);
    }

    static UpdateResult skipped(String from, String to, boolean force, String message) {
        return new UpdateResult(Outcome.SKIPPED, from, to, false, force, null, message);
    }
}