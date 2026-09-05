package com.potatotv.paccclient.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 红屏激活状态持久化：红屏触发时写入，解除时清除；进程重启后由客户端启动流程读回并恢复全屏红屏，
 * 保证管理员查端解除前状态不丢失。
 */
public final class RedScreenStatePersistence {

    private final Path file;
    private final String password;

    public RedScreenStatePersistence(Path file, String password) {
        this.file = file;
        this.password = password;
    }

    /** 红屏触发上下文，用于重启恢复。 */
    public record Active(int level, String cheatType, String masked, String risk) {
    }

    public Optional<Active> loadActive() {
        try {
            byte[] blob = LocalSecureStore.load(file, password);
            List<String> parts = LocalSecureStore.decodeStrings(blob);
            if (parts.size() < 5) return Optional.empty();
            return Optional.of(new Active(Integer.parseInt(parts.get(0)), parts.get(1), parts.get(2), parts.get(3)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void setActive(int level, String cheatType, String masked, String risk) {
        try {
            LocalSecureStore.save(file, password,
                    LocalSecureStore.encodeStrings(List.of(String.valueOf(level), cheatType, masked, risk, "active")));
        } catch (IOException e) {
            System.err.println("[PTV-Client] 红屏状态持久化失败: " + e.getMessage());
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("[PTV-Client] 红屏状态清除失败: " + e.getMessage());
        }
    }
}