package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.plugin.PluginRuntime;
import com.potatotv.pacc.repository.PluginRepository;
import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.plugin.PluginManager;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §4.2.2 插件运行时接口（受 X-Admin-Key / 管理会话保护）。
 * <p>与插件市场 {@code /api/admin/plugins} 协同：市集条目（{@code t_plugin}）审核上架后，
 * 可经此接口热加载 / 卸载为运行时插件。</p>
 */
@RestController
@RequestMapping("/api/admin/plugins/runtime")
@RequiredArgsConstructor
public class PluginRuntimeController {

    private final PluginManager pluginManager;
    private final PluginRepository pluginRepository;

    /** 已加载/登记的运行时插件列表。 */
    @GetMapping
    public List<Map<String, Object>> list() {
        return pluginManager.listPlugins().stream().map(this::view).toList();
    }

    /**
     * 按插件市场条目热加载：路径取请求体 path（相对插件目录的相对路径，越界由服务层拒绝），
     * 缺省回退市场条目的 package_url。
     */
    @PostMapping("/{id}/load")
    @RequirePermission("system:update")
    public ResponseEntity<?> load(@PathVariable String id,
                                  @RequestBody(required = false) Map<String, Object> body,
                                  HttpServletRequest request) {
        try {
            String path = body == null ? null : str(body.get("path"));
            if (path == null) {
                path = pluginRepository.findById(id).map(p -> p.getPackageUrl()).orElse(null);
            }
            if (path == null || path.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "缺少插件包路径 path"));
            }
            return ResponseEntity.ok(view(pluginManager.loadPlugin(id, path)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 卸载运行时插件（destroy + 关闭类加载器）。 */
    @PostMapping("/{id}/unload")
    @RequirePermission("system:update")
    public ResponseEntity<?> unload(@PathVariable String id) {
        try {
            return ResponseEntity.ok(view(pluginManager.unloadPlugin(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> view(PluginRuntime r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getPluginId());
        m.put("name", r.getName());
        m.put("version", r.getVersion());
        m.put("state", r.getState());
        m.put("cpuMs", r.getCpuMs());
        m.put("errorCount", r.getErrorCount());
        m.put("declaredApis", r.getDeclaredApis());
        m.put("classPath", r.getClassPath());
        m.put("loadedAt", r.getLoadedAt());
        m.put("updatedAt", r.getUpdatedAt());
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}