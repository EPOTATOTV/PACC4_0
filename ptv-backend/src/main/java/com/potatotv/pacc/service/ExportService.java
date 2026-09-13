package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Appeal;
import com.potatotv.pacc.domain.CheatRecord;
import com.potatotv.pacc.domain.ExportTask;
import com.potatotv.pacc.domain.RedscreenAlert;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import com.potatotv.pacc.repository.ExportTaskRepository;
import com.potatotv.pacc.repository.RedscreenAlertRepository;
import com.potatotv.pacc.domain.Account;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 数据导出服务：异步将选定主题（账号/红屏/作弊记录/申诉）导出为 CSV 到本地目录，
 * 任务就绪后凭一次性 download_key 取文件。文件默认 30 分钟后过期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT;
    private static final long EXPIRE_SECONDS = 1800L; // 30 分钟

    private final ExportTaskRepository taskRepository;
    private final AccountRepository accountRepository;
    private final RedscreenAlertRepository redscreenAlertRepository;
    private final CheatRecordRepository cheatRecordRepository;
    private final AppealRepository appealRepository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pacc-export-worker");
        t.setDaemon(true);
        return t;
    });

    /** 提交导出任务并异步执行。 */
    @Transactional
    public ExportTask submit(String subject, String filters, String requestedBy) {
        ExportTask task = ExportTask.builder()
                .id(UUID.randomUUID().toString())
                .subject(subject == null || subject.isBlank() ? "accounts" : subject.trim())
                .filters(filters)
                .requestedBy(requestedBy)
                .status("QUEUED")
                .format("csv")
                .requestedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(EXPIRE_SECONDS))
                .build();
        taskRepository.save(task);
        executor.execute(() -> run(task.getId()));
        return task;
    }

    /** 执行导出（在工作线程运行，不参与事务）。 */
    private void run(String taskId) {
        ExportTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return;
        try {
            task.setStatus("RUNNING");
            taskRepository.save(task);
            Path dir = dirs();
            Path file = dir.resolve(task.getId() + ".csv");
            String downloadKey = UUID.randomUUID().toString();
            long rows = doExport(task.getSubject(), file);
            task.setStatus("READY");
            task.setRowCount((int) rows);
            task.setFilePath(file.toAbsolutePath().toString());
            task.setDownloadKey(downloadKey);
            task.setCompletedAt(Instant.now());
            taskRepository.save(task);
            log.info("导出完成 task={} subject={} rows={}", taskId, task.getSubject(), rows);
        } catch (Exception e) {
            task.setStatus("FAILED");
            task.setErrorMsg(truncate(e.getMessage(), 500));
            task.setCompletedAt(Instant.now());
            taskRepository.save(task);
            log.error("导出失败 task={} err={}", taskId, e.getMessage());
        }
    }

    private long doExport(String subject, Path file) throws IOException {
        return switch (subject) {
            case "redscreens" -> writeRedscreens(file);
            case "cheat_records" -> writeCheatRecords(file);
            case "appeals" -> writeAppeals(file);
            default -> writeAccounts(file);
        };
    }

    private long writeAccounts(Path file) throws IOException {
        String header = "pteid,email,status,reputation,registeredAt\n";
        StringBuilder sb = new StringBuilder(header);
        List<Account> accs = accountRepository.findAll();
        for (Account a : accs) {
            sb.append(csv(a.getPteid())).append(',')
              .append(csv(a.getEmail())).append(',')
              .append(csv(a.getStatus())).append(',')
              .append(a.getReputation()).append(',')
              .append(a.getRegisteredAt() == null ? "" : TS.format(a.getRegisteredAt())).append('\n');
        }
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        return accs.size();
    }

    private long writeRedscreens(Path file) throws IOException {
        String header = "alertId,pteid,level,cheatType,riskScore,state,occurredAt\n";
        StringBuilder sb = new StringBuilder(header);
        List<RedscreenAlert> rows = redscreenAlertRepository.findAll();
        for (RedscreenAlert a : rows) {
            sb.append(csv(a.getAlertId())).append(',')
              .append(csv(a.getPteid())).append(',')
              .append(a.getLevel()).append(',')
              .append(csv(a.getCheatType())).append(',')
              .append(a.getRiskScore()).append(',')
              .append(csv(a.getState())).append(',')
              .append(a.getOccurredAt() == null ? "" : TS.format(a.getOccurredAt())).append('\n');
        }
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        return rows.size();
    }

    private long writeCheatRecords(Path file) throws IOException {
        String header = "recordId,pteid,alertId,cheatType,level,riskScore,revoked,occurredAt\n";
        StringBuilder sb = new StringBuilder(header);
        List<CheatRecord> rows = cheatRecordRepository.findAll();
        for (CheatRecord r : rows) {
            sb.append(csv(r.getRecordId())).append(',')
              .append(csv(r.getPteid())).append(',')
              .append(csv(r.getAlertId())).append(',')
              .append(csv(r.getCheatType())).append(',')
              .append(r.getLevel()).append(',')
              .append(r.getRiskScore()).append(',')
              .append(r.isRevoked()).append(',')
              .append(r.getOccurredAt() == null ? "" : TS.format(r.getOccurredAt())).append('\n');
        }
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        return rows.size();
    }

    private long writeAppeals(Path file) throws IOException {
        String header = "appealId,pteid,alertId,status,reviewStage,createdAt,reviewedAt\n";
        StringBuilder sb = new StringBuilder(header);
        List<Appeal> rows = appealRepository.findAll();
        for (Appeal a : rows) {
            sb.append(csv(a.getAppealId())).append(',')
              .append(csv(a.getPteid())).append(',')
              .append(csv(a.getAlertId())).append(',')
              .append(csv(a.getStatus())).append(',')
              .append(csv(a.getReviewStage())).append(',')
              .append(a.getCreatedAt() == null ? "" : TS.format(a.getCreatedAt())).append(',')
              .append(a.getReviewedAt() == null ? "" : TS.format(a.getReviewedAt())).append('\n');
        }
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        return rows.size();
    }

    /** 一次性下载：校验任务就绪且 download_key 匹配，随后令凭证失效。 */
    @Transactional
    public ExportTask consumeDownload(String taskId, String key) {
        ExportTask t = taskRepository.findById(taskId).orElse(null);
        if (t == null) return null;
        if (!"READY".equals(t.getStatus())) return null;
        if (t.getDownloadKey() == null || !t.getDownloadKey().equals(key)) return null;
        // 凭证一次性
        t.setDownloadKey(null);
        t.setStatus("EXPIRED");
        taskRepository.save(t);
        return t;
    }

    public List<ExportTask> list(String requestedBy) {
        return taskRepository.findByRequestedByOrderByRequestedAtDesc(requestedBy);
    }

    private Path dirs() throws IOException {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "pacc-export");
        if (!Files.exists(dir)) Files.createDirectories(dir);
        return dir;
    }

    private static String csv(String s) {
        if (s == null) return "";
        boolean needQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0;
        String v = s.replace("\"", "\"\"");
        return needQuote ? '"' + v + '"' : v;
    }

    private static String truncate(String s, int len) {
        if (s == null) return null;
        return s.length() <= len ? s : s.substring(0, len);
    }
}