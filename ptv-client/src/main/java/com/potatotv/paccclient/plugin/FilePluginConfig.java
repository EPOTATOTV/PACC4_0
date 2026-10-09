package com.potatotv.paccclient.plugin;

import com.potatotv.paccclient.spi.PluginConfig;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 文件落地的插件配置（文档 §2.2「插件私有KV，持久化到本地」）。
 *
 * <p>存为 Java {@code .properties}（UTF-8），路径由加载器按插件 id 分配。写失败只记日志不抛出：
 * 配置丢失不应让插件崩溃。</p>
 */
public final class FilePluginConfig implements PluginConfig {

    private final Path file;
    private final Properties properties = new Properties();

    public FilePluginConfig(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            System.err.println("[PTV-Plugin] 配置读取失败 " + file + ": " + e.getMessage());
        }
    }

    @Override
    public String get(String key) {
        return key == null ? null : properties.getProperty(key);
    }

    @Override
    public void put(String key, String value) {
        if (key == null) {
            return;
        }
        if (value == null) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value);
        }
        save();
    }

    @Override
    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            out.put(name, properties.getProperty(name));
        }
        return out;
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                properties.store(writer, "PACC plugin config");
            }
        } catch (IOException e) {
            System.err.println("[PTV-Plugin] 配置写入失败 " + file + ": " + e.getMessage());
        }
    }
}