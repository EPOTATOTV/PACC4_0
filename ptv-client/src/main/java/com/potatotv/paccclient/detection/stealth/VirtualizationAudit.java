package com.potatotv.paccclient.detection.stealth;

import java.net.NetworkInterface;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 反虚拟机检测（文档 §4.3.2）。
 *
 * <p>多路取证，命中任一路即记为虚拟化环境：</p>
 * <ul>
 *   <li><b>MAC 前缀</b>：00:0C:29 / 00:50:56（VMware）、08:00:27（VirtualBox）、00:15:5D（Hyper-V）、
 *       52:54:00（QEMU/KVM）、00:16:3E（Xen）——纯 JDK 可枚举；</li>
 *   <li><b>Guest 组件与虚拟设备</b>：Windows 查 VMware Tools / VirtualBox Guest Additions 注册表键
 *       与 Hyper-V 的 vmbus 服务键；Linux 读 DMI 型号与厂商；</li>
 *   <li><b>Hypervisor 位</b>：Linux 读 {@code /proc/cpuinfo} 的 {@code hypervisor} 标志，
 *       Windows 走 {@code wmic computersystem get hypervisorpresent}。</li>
 * </ul>
 *
 * <p>虚拟机不等于作弊，这里只提供事实；是否上报由 {@code StealthDetector} 按风险模型决定。</p>
 */
final class VirtualizationAudit {

    /** MAC 前缀 → 虚拟化厂商。 */
    private static final Map<String, String> MAC_PREFIXES = new LinkedHashMap<>(Map.of(
            "000C29", "VMware", "005056", "VMware", "080027", "VirtualBox",
            "00155D", "Hyper-V", "525400", "QEMU/KVM", "00163E", "Xen"));

    /** 虚拟化厂商关键词（DMI / BIOS / 注册表文本命中）。 */
    private static final String[] VENDOR_KEYWORDS = {
            "vmware", "virtualbox", "vbox", "qemu", "kvm", "xen", "hyper-v", "hyperv", "parallels", "bochs"};

    private VirtualizationAudit() {
    }

    /** 审计结果；{@code vm} / {@code hypervisor} 未知时为 {@code null}。 */
    record Audit(Boolean vm, Boolean hypervisor, List<String> evidence) {
    }

    static Audit audit() {
        List<String> evidence = new ArrayList<>();
        collectMacEvidence(evidence);
        collectPlatformEvidence(evidence);
        Boolean hypervisor = hypervisorPresent();
        if (Boolean.TRUE.equals(hypervisor)) evidence.add("hypervisor-bit");
        // 有取证结果才给出 vm 结论：没有证据也可能是查不到（非受支持平台），此时保持未知
        Boolean vm = evidence.isEmpty() ? null : Boolean.TRUE;
        if (vm == null && (OsCommand.isWindows() || OsCommand.isLinux())) vm = Boolean.FALSE;
        return new Audit(vm, hypervisor, List.copyOf(evidence));
    }

    private static void collectMacEvidence(List<String> evidence) {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                byte[] mac = ni.getHardwareAddress();
                if (mac == null || mac.length < 6) continue;
                String prefix = String.format("%02X%02X%02X", mac[0], mac[1], mac[2]);
                String vendor = MAC_PREFIXES.get(prefix);
                if (vendor != null) evidence.add("mac:" + prefix + "(" + vendor + ")");
            }
        } catch (Exception e) {
            // 网络接口不可枚举：跳过该路证据
        }
    }

    private static void collectPlatformEvidence(List<String> evidence) {
        if (OsCommand.isWindows()) {
            collectWindowsEvidence(evidence);
        } else if (OsCommand.isLinux()) {
            for (Path f : new Path[]{Path.of("/sys/class/dmi/id/product_name"),
                    Path.of("/sys/class/dmi/id/sys_vendor"), Path.of("/sys/class/dmi/id/board_vendor")}) {
                Optional<String> text = OsCommand.read(f);
                text.ifPresent(t -> hit(evidence, f.getFileName() + ":" + t.trim()));
            }
        }
    }

    private static void collectWindowsEvidence(List<String> evidence) {
        checkWindowsKey(evidence, "HKLM\\SOFTWARE\\VMware, Inc.\\VMware Tools", "vmware-tools");
        checkWindowsKey(evidence, "HKLM\\SOFTWARE\\Oracle\\VirtualBox Guest Additions", "vbox-guest-additions");
        checkWindowsKey(evidence, "HKLM\\SYSTEM\\CurrentControlSet\\Services\\vmbus", "hyperv-vmbus");
        // BIOS 描述里带着虚拟化厂商名（DMI 文本）
        OsCommand.output("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\BIOS", "/v", "SystemManufacturer")
                .ifPresent(out -> hit(evidence, "bios-manufacturer:" + valueOf(out)));
        OsCommand.output("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\BIOS", "/v", "SystemProductName")
                .ifPresent(out -> hit(evidence, "bios-product:" + valueOf(out)));
    }

    /** 注册表键存在即命中（reg query 成功表示键存在）。 */
    private static void checkWindowsKey(List<String> evidence, String key, String label) {
        if (OsCommand.output("reg", "query", key).isPresent()) evidence.add(label);
    }

    private static void hit(List<String> evidence, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : VENDOR_KEYWORDS) {
            if (lower.contains(keyword)) {
                evidence.add(text.trim());
                return;
            }
        }
    }

    private static String valueOf(String regOutput) {
        int i = regOutput.lastIndexOf("REG_SZ");
        return i < 0 ? "" : regOutput.substring(i + "REG_SZ".length()).trim();
    }

    private static Boolean hypervisorPresent() {
        if (OsCommand.isLinux()) {
            String cpuinfo = OsCommand.read(Path.of("/proc/cpuinfo")).orElse("");
            if (cpuinfo.isEmpty()) return null;
            boolean flagged = false;
            for (String line : cpuinfo.split("\\R")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("flags") && line.contains("hypervisor")) {
                    flagged = true;
                    break;
                }
            }
            return flagged;
        }
        if (OsCommand.isWindows()) {
            Optional<String> out = OsCommand.output("wmic", "computersystem", "get", "hypervisorpresent");
            if (out.isEmpty()) return null;
            String lower = out.get().toLowerCase(Locale.ROOT);
            if (!lower.contains("true") && !lower.contains("false")) return null;
            return lower.contains("true");
        }
        return null;
    }
}