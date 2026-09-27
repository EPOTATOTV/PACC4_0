package com.potatotv.pcu;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 测试用的平台适配层：不碰真实进程，只记录停/起调用。 */
final class StubAdapter implements PlatformAdapter {

    final Path installDir;
    final Path tempDir;
    final List<String> calls = new ArrayList<>();
    final List<String> notifications = new ArrayList<>();

    boolean failStart;
    boolean failStop;
    boolean enoughSpace = true;

    StubAdapter(Path installDir, Path tempDir) {
        this.installDir = installDir;
        this.tempDir = tempDir;
    }

    @Override
    public void stopPacc() {
        calls.add("stop");
        if (failStop) {
            throw new PcuException("测试：停服务失败");
        }
    }

    @Override
    public void startPacc() {
        calls.add("start");
        if (failStart) {
            throw new PcuException("测试：起服务失败");
        }
    }

    @Override
    public Path getInstallDir() {
        return installDir;
    }

    @Override
    public Path getTempDir() {
        return tempDir;
    }

    @Override
    public CompletableFuture<Boolean> requestStoragePermission() {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public void showUpdateNotification(String title, String message) {
        notifications.add(title + "|" + message);
    }

    @Override
    public boolean hasEnoughSpace(long requiredBytes) {
        return enoughSpace;
    }
}