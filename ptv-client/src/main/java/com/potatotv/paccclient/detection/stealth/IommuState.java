package com.potatotv.paccclient.detection.stealth;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IOMMU / 内核 DMA 保护状态（文档 §4.1.2）。
 *
 * <p>DMA 作弊要直读物理内存，通常得关掉或绕开 IOMMU。Windows 读
 * {@code HKLM\SYSTEM\CurrentControlSet\Control\DeviceGuard\Scenarios\KernelDmaProtection} 的
 * {@code Enabled}；Linux 看 {@code /sys/kernel/iommu_groups} 是否有分组，并检查内核命令行是否显式关闭。</p>
 *
 * <p>返回三态：{@code TRUE}/{@code FALSE} 必须来自实测；查不到（键不存在、非受支持平台）返回
 * {@code null}，上层既不报警也不计入覆盖度——「没配策略」不等于「IOMMU 关着」。</p>
 */
final class IommuState {

    private static final String WINDOWS_KEY =
            "HKLM\\SYSTEM\\CurrentControlSet\\Control\\DeviceGuard\\Scenarios\\KernelDmaProtection";
    private static final Pattern DWORD = Pattern.compile("Enabled\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)");
    private static final Path LINUX_GROUPS = Path.of("/sys/kernel/iommu_groups");

    private IommuState() {
    }

    /** 是否已启用 DMA 保护（IOMMU 生效）。未知返回 {@code null}。 */
    static Boolean dmaProtectionEnabled() {
        if (OsCommand.isWindows()) return windows();
        if (OsCommand.isLinux()) return linux();
        return null;
    }

    private static Boolean windows() {
        Optional<String> out = OsCommand.output("reg", "query", WINDOWS_KEY, "/v", "Enabled");
        if (out.isEmpty()) return null;
        Matcher m = DWORD.matcher(out.get());
        if (!m.find()) return null;
        return "1".equals(m.group(1).replaceAll("^0+(?=.)", ""));
    }

    private static Boolean linux() {
        String cmdline = OsCommand.read(Path.of("/proc/cmdline")).orElse("").toLowerCase(Locale.ROOT);
        if (cmdline.contains("iommu=off") || cmdline.contains("intel_iommu=off")
                || cmdline.contains("amd_iommu=off")) {
            return Boolean.FALSE;
        }
        if (OsCommand.nonEmptyDir(LINUX_GROUPS)) return Boolean.TRUE;
        // sysfs 存在但没有任何分组：内核未启用 IOMMU（容器/裁剪内核同样命中，故仅在 /proc 可读时下结论）
        return OsCommand.read(Path.of("/proc/cmdline")).isPresent() ? Boolean.FALSE : null;
    }
}