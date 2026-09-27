package com.potatotv.pcu;

import java.util.logging.Logger;

/**
 * 回滚到旧版本（设计文档 §4.6.2）：停止新版本 → 从备份恢复 → 启动旧版本。
 *
 * <p>走到这里说明新版本已经装好并尝试启动过了，只是没通过健康检查。上报「回滚事件」由
 * 编排层负责——那里才有上报器，也让上报只在一个地方发生。</p>
 */
public final class UpdateRollbacker {

    private static final Logger LOG = Logger.getLogger(UpdateRollbacker.class.getName());

    private final PcuConfig config;
    private final BackupManager backups;

    public UpdateRollbacker(PcuConfig config, BackupManager backups) {
        this.config = config;
        this.backups = backups;
    }

    /** 执行回滚；任何一步失败都抛异常，由调用方判定并上报失败。 */
    public void rollback(BackupManager.BackupHandle handle) {
        if (handle == null || handle.isEmpty()) {
            throw new PcuException("没有可用于回滚的备份，无法回滚");
        }
        LOG.warning(() -> "开始回滚到 " + handle.version() + "：" + handle.dir());
        config.adapter().stopPacc();
        backups.restore(handle);
        config.adapter().startPacc();
        LOG.info(() -> "已回滚到 " + handle.version());
    }
}