package com.potatotv.pacc.service.automation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * §4.3.3 动作注册表：按动作编码索引全部 {@link AutomationActionHandler} 实现。
 */
@Component
public class AutomationActionRegistry {

    private final Map<String, AutomationActionHandler> handlers = new LinkedHashMap<>();

    public AutomationActionRegistry(List<AutomationActionHandler> discovered) {
        for (AutomationActionHandler h : discovered) {
            handlers.put(h.code(), h);
        }
    }

    public Optional<AutomationActionHandler> find(String code) {
        return Optional.ofNullable(code == null ? null : handlers.get(code));
    }

    public java.util.Set<String> codes() {
        return java.util.Set.copyOf(handlers.keySet());
    }
}