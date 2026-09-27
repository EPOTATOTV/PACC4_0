package com.potatotv.pacc.service.automation;

import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

/**
 * 基于 {@code com.sun.management.OperatingSystemMXBean} 的系统负载探针。
 *
 * <p>优先取进程 CPU 负载（{@code getCpuLoad()}）；在拿不到（返回负值）或不支持该扩展的平台上，
 * 回退到系统平均负载除以可用核数，并统一归一到 0.0~1.0。</p>
 */
@Component
public class OsBeanSystemLoadProvider implements SystemLoadProvider {

    @Override
    public double cpuLoad() {
        try {
            OperatingSystemMXBean base = ManagementFactory.getOperatingSystemMXBean();
            int cores = Math.max(1, base.getAvailableProcessors());
            if (base instanceof com.sun.management.OperatingSystemMXBean extended) {
                double cpu = extended.getCpuLoad();
                if (cpu >= 0) {
                    return clamp(cpu);
                }
            }
            double average = base.getSystemLoadAverage();
            return average >= 0 ? clamp(average / cores) : 0.0;
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}