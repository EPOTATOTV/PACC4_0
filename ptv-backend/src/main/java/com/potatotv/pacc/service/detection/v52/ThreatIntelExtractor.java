package com.potatotv.pacc.service.detection.v52;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.IocIndicator;
import com.potatotv.pacc.repository.IocIndicatorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v5.2 §6.3 威胁情报自动提取：红屏事件的明细里直接挖 IOC，不再依赖人工录入。
 *
 * <p>提取对象（文档 §6.3 清单）：可疑进程 / 驱动 / 模块路径、文件哈希（SHA-256 / MD5）、
 * PCIe 设备（{@code VID:DID}）、USB 设备（{@code VID_xxxx&PID_xxxx}）、注册表路径、
 * IP / 域名、作弊客户端家族名。明细是客户端上报的自由 JSON，这里按「字段名 + 值形态」双路识别：
 * 字段名提示语义（agent / attach / pcie / thread），值形态决定类型（哈希 / IP / 扩展名）。</p>
 *
 * <p>去重与命中计数：同 {@code (value, type)} 已存在时只累加 {@code hitCount} 并刷新
 * {@code lastSeen}，不重复入库；新指标即时写入（与红屏同一事务，延迟就是一次插入）。</p>
 *
 * <p>归族：命中已知客户端关键词（wurst / impact / sigma 等）以客户端名为族；否则同类型下
 * 复用「同前缀既有 IOC」的族（同目录/同前缀视为一族）；再否则以事件类型（{@code dma_cheat}、
 * {@code reflective_dll} 等）为族。规则简单，但族标签可用、可追溯，不做无依据的推断。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThreatIntelExtractor {

    /** 单事件入库上限：明细可能很长，防止一次事件刷爆 IOC 库。 */
    static final int MAX_PER_EVENT = 40;
    /** IOC 值长度上限（与 {@code t_ioc_indicator.value} 列宽一致）。 */
    private static final int MAX_VALUE_LEN = 255;
    /** 归族前缀长度：取前 24 字符做「同目录 / 同前缀」判断。 */
    private static final int FAMILY_PREFIX_LEN = 24;

    /** 已知作弊客户端关键词 → 家族名。 */
    private static final Map<String, String> KNOWN_CLIENTS = Map.ofEntries(
            Map.entry("wurst", "Wurst"), Map.entry("impact", "Impact"), Map.entry("sigma", "Sigma"),
            Map.entry("meteor", "Meteor"), Map.entry("future", "Future"), Map.entry("kami", "Kami"),
            Map.entry("aristois", "Aristois"), Map.entry("bleachhack", "BleachHack"),
            Map.entry("baritone", "Baritone"), Map.entry("lambda", "Lambda"),
            Map.entry("pcileech", "PCILeech"), Map.entry("leechcore", "LeechCore"),
            Map.entry("screamer", "ScreamerM2"), Map.entry("frida", "Frida"));

    private static final Pattern SHA256 = Pattern.compile("\\b[0-9a-fA-F]{64}\\b");
    private static final Pattern MD5 = Pattern.compile("\\b[0-9a-fA-F]{32}\\b");
    private static final Pattern PCI_ID = Pattern.compile("\\b([0-9A-Fa-f]{4}):([0-9A-Fa-f]{4})\\b");
    private static final Pattern USB_ID = Pattern.compile("VID_([0-9A-Fa-f]{4})&PID_([0-9A-Fa-f]{4})");
    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\b");
    private static final Pattern DOMAIN = Pattern.compile("\\b((?:[a-zA-Z0-9-]+\\.)+(?:com|net|org|cn|io|ru|top|xyz|cc|tk))\\b");

    private final IocIndicatorRepository iocs;
    private final ObjectMapper mapper;

    /** 提取结果。 */
    public record Extraction(List<IocIndicator> created, List<IocIndicator> matched, int candidates) {

        public int total() {
            return created.size() + matched.size();
        }
    }

    /** 单个候选指标。 */
    record Candidate(String value, String type, int severity) {
    }

    /**
     * 从红屏事件明细提取并入库 IOC。
     *
     * @param cheatType 事件检测类型（作为兜底家族名）
     * @param detailJson 客户端上报的事件明细（自由 JSON 或纯文本）
     * @param sourceId  来源标识（红屏 alertId，便于回溯）
     */
    @Transactional
    public Extraction extractAndStore(String cheatType, String detailJson, String sourceId) {
        List<Candidate> candidates = extract(detailJson);
        List<IocIndicator> created = new ArrayList<>();
        List<IocIndicator> matched = new ArrayList<>();
        for (Candidate c : candidates) {
            Optional<IocIndicator> existing = iocs.findByValueAndType(c.value(), c.type());
            if (existing.isPresent()) {
                IocIndicator ioc = existing.get();
                ioc.setHitCount(ioc.getHitCount() + 1);
                ioc.setLastSeen(Instant.now());
                if (sourceId != null && !sourceId.isBlank()) ioc.setSourceId(sourceId);
                iocs.save(ioc);
                matched.add(ioc);
                continue;
            }
            IocIndicator ioc = IocIndicator.builder()
                    .value(c.value())
                    .type(c.type())
                    .sourceId(sourceId)
                    .sourceFamily(familyOf(cheatType, c))
                    .severity(c.severity())
                    .state(IocIndicator.State.OPEN.name())
                    .hitCount(1)
                    .firstSeen(Instant.now())
                    .lastSeen(Instant.now())
                    .build();
            iocs.save(ioc);
            created.add(ioc);
        }
        return new Extraction(List.copyOf(created), List.copyOf(matched), candidates.size());
    }

    /** 纯提取（不落库）：遍历明细 JSON 的每个字符串值，按字段名与值形态归类去重后按严重度排序。 */
    List<Candidate> extract(String detailJson) {
        Map<String, Candidate> unique = new LinkedHashMap<>();
        if (detailJson == null || detailJson.isBlank()) return List.of();
        try {
            JsonNode root = mapper.readTree(detailJson);
            walk(root, null, unique);
        } catch (Exception e) {
            // 明细不是合法 JSON（老客户端发自由文本）：按纯文本扫一遍，至少捞出哈希 / IP / 路径
            scanText(detailJson, unique);
        }
        List<Candidate> out = new ArrayList<>(unique.values());
        out.sort(Comparator.comparingInt(Candidate::severity).reversed());
        return out.size() <= MAX_PER_EVENT ? out : List.copyOf(out.subList(0, MAX_PER_EVENT));
    }

    private void walk(JsonNode node, String key, Map<String, Candidate> out) {
        if (node == null) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> walk(e.getValue(), e.getKey(), out));
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) walk(item, key, out);
            return;
        }
        if (node.isTextual()) {
            classify(key, node.asText(""), out);
        }
    }

    /** 纯文本兜底：按分隔符切开逐个识别。 */
    private void scanText(String text, Map<String, Candidate> out) {
        for (String token : text.split("[\\s,;|\\[\\]{}()\"']+")) {
            classify(null, token, out);
        }
    }

    /**
     * 单值归类：值形态优先（哈希 / IP / 设备号 / 扩展名），字段名补充语义（agent / attach / pcie / thread）。
     */
    private void classify(String key, String raw, Map<String, Candidate> out) {
        if (raw == null) return;
        String value = raw.trim();
        if (value.isEmpty() || value.length() > MAX_VALUE_LEN) return;
        String lower = value.toLowerCase(Locale.ROOT);
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);

        String family = knownClient(lower);
        if (family != null) {
            add(out, new Candidate(family, "CLIENT_FAMILY", 4));
        }
        if (SHA256.matcher(value).find()) {
            add(out, new Candidate(sha(value), "FILE_HASH", 4));
        } else if (MD5.matcher(value).find()) {
            add(out, new Candidate(md5(value), "FILE_HASH", 3));
        }
        Matcher usb = USB_ID.matcher(value);
        if (usb.find()) {
            add(out, new Candidate("USB:" + usb.group(1).toUpperCase(Locale.ROOT) + ":"
                    + usb.group(2).toUpperCase(Locale.ROOT), "USB_DEVICE", 3));
        }
        if (k.contains("pcie") || k.contains("dma") || lower.startsWith("pci")) {
            Matcher pci = PCI_ID.matcher(value);
            if (pci.find()) {
                add(out, new Candidate("PCI:" + pci.group(1).toUpperCase(Locale.ROOT) + ":"
                        + pci.group(2).toUpperCase(Locale.ROOT), "HW_DEVICE", 4));
            }
        }
        if (IPV4.matcher(value).find()) {
            add(out, new Candidate(IPv4Only(value), "IP", 3));
        } else {
            Matcher domain = DOMAIN.matcher(value);
            if (domain.find()) add(out, new Candidate(domain.group(1), "DOMAIN", 3));
        }
        if (lower.startsWith("hklm\\") || lower.startsWith("hkey_")) {
            add(out, new Candidate(value, "REGISTRY", 3));
        }
        if (lower.endsWith(".sys") || lower.endsWith(".drv")) {
            add(out, new Candidate(value, "DRIVER", 4));
        } else if (lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib")) {
            add(out, new Candidate(value, "MODULE", 3));
        } else if (lower.endsWith(".exe") || lower.endsWith(".jar") || lower.endsWith(".paccm")) {
            // jar / exe 一律按文件路径归类（javaagent 的 jar 也是文件，不做「模块」语义重载）
            add(out, new Candidate(value, "FILE_PATH", 3));
        }
        if (k.contains("attach")) {
            add(out, new Candidate(value, "FILE_PATH", 3));
        } else if (k.contains("thread")) {
            add(out, new Candidate(value, "STRING", 2));
        } else if (k.equals("process") || k.contains("process")) {
            add(out, new Candidate(value, "PROCESS", 3));
        }
    }

    /** 命中已知客户端关键词时返回家族名。 */
    private static String knownClient(String lowerValue) {
        for (Map.Entry<String, String> e : KNOWN_CLIENTS.entrySet()) {
            if (lowerValue.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /**
     * 归族：已知客户端 → 客户端名；否则同类型同前缀的既有 IOC 的族；再否则事件类型。
     * 前缀复用让「同一目录下的一批外挂组件」自然落到同一族，比单靠事件类型更贴近情报分组。
     */
    private String familyOf(String cheatType, Candidate c) {
        String client = knownClient(c.value().toLowerCase(Locale.ROOT));
        if (client != null) return client;
        if (c.value().length() >= FAMILY_PREFIX_LEN) {
            String prefix = c.value().substring(0, FAMILY_PREFIX_LEN);
            Optional<IocIndicator> sibling = iocs.findFirstByTypeAndValueStartingWith(c.type(), prefix);
            if (sibling.isPresent() && sibling.get().getSourceFamily() != null
                    && !sibling.get().getSourceFamily().isBlank()) {
                return sibling.get().getSourceFamily();
            }
        }
        return cheatType == null || cheatType.isBlank() ? "unknown" : cheatType;
    }

    /** 同类型同值去重：先到者保留（严重度取两者较高）。 */
    private static void add(Map<String, Candidate> out, Candidate c) {
        out.merge(c.type() + "|" + c.value(), c,
                (a, b) -> a.severity() >= b.severity() ? a : b);
    }

    private static String sha(String value) {
        Matcher m = SHA256.matcher(value);
        return m.find() ? m.group().toLowerCase(Locale.ROOT) : value;
    }

    private static String md5(String value) {
        Matcher m = MD5.matcher(value);
        return m.find() ? m.group().toLowerCase(Locale.ROOT) : value;
    }

    private static String IPv4Only(String value) {
        Matcher m = IPV4.matcher(value);
        return m.find() ? m.group(1) : value;
    }
}