package com.potatotv.prl.engine;

import java.util.Arrays;
import java.util.Objects;

/**
 * 规则的一个版本（设计文档 §2.13.1 版本表 {@code prl_rule_versions} 的一行）。
 *
 * <p>是个类而不是 record，只因为 {@code bytecode} 是 {@code byte[]}：record 生成的
 * {@code equals} 对数组是引用比较，两个内容相同的版本会被判成不等，回滚与灰度对比都会因此失效。
 * 这里给出按内容的 {@code equals}/{@code hashCode}。</p>
 *
 * <p>{@code checksum} 取<strong>源码</strong>的 SHA-256，不是字节码的。字节码是源码的派生结果，
 * 而实际会被人改、被粘贴错的是源码；校验源码能发现「同一个版本号下的内容被换了」，
 * 这正是管理端最需要拦的一种事故。</p>
 *
 * @param ruleName    规则名
 * @param version     语义化版本，如 {@code 1.2.0}
 * @param source      PRL 源码
 * @param bytecode    编译产物（{@code .prlc} 字节）
 * @param checksum    源码的 SHA-256（64 位十六进制）
 * @param author      作者
 * @param status      发布状态
 * @param createdAtMs 创建时间（毫秒时间戳）
 * @param approvedBy  审批人；生产环境发布必需，草稿为 {@code null}
 * @param rollbackTo  回滚目标版本号；由发布流程回填，草稿为 {@code null}
 */
public final class RuleVersion {

    private final String ruleName;
    private final String version;
    private final String source;
    private final byte[] bytecode;
    private final String checksum;
    private final String author;
    private final RuleStatus status;
    private final long createdAtMs;
    private final String approvedBy;
    private final String rollbackTo;

    public RuleVersion(String ruleName, String version, String source, byte[] bytecode,
                       String author, RuleStatus status, long createdAtMs) {
        this(ruleName, version, source, bytecode, Checksums.sha256(source), author, status,
                createdAtMs, null, null);
    }

    public RuleVersion(String ruleName, String version, String source, byte[] bytecode, String checksum,
                       String author, RuleStatus status, long createdAtMs, String approvedBy,
                       String rollbackTo) {
        this.ruleName = Objects.requireNonNull(ruleName, "ruleName");
        this.version = Objects.requireNonNull(version, "version");
        this.source = Objects.requireNonNull(source, "source");
        this.bytecode = bytecode == null ? new byte[0] : bytecode.clone();
        this.checksum = checksum == null ? Checksums.sha256(source) : checksum;
        this.author = author;
        this.status = status == null ? RuleStatus.DRAFT : status;
        this.createdAtMs = createdAtMs;
        this.approvedBy = approvedBy;
        this.rollbackTo = rollbackTo;
    }

    public String ruleName() {
        return ruleName;
    }

    public String version() {
        return version;
    }

    public String source() {
        return source;
    }

    /** 编译产物；调用方拿到的是副本，改不动表里的数据。 */
    public byte[] bytecode() {
        return bytecode.clone();
    }

    public String checksum() {
        return checksum;
    }

    public String author() {
        return author;
    }

    public RuleStatus status() {
        return status;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public String approvedBy() {
        return approvedBy;
    }

    public String rollbackTo() {
        return rollbackTo;
    }

    public RuleVersion withStatus(RuleStatus newStatus, String approver, String rollbackTarget) {
        return new RuleVersion(ruleName, version, source, bytecode, checksum, author, newStatus,
                createdAtMs, approver, rollbackTarget);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof RuleVersion that
                && createdAtMs == that.createdAtMs
                && ruleName.equals(that.ruleName)
                && version.equals(that.version)
                && source.equals(that.source)
                && Arrays.equals(bytecode, that.bytecode)
                && checksum.equals(that.checksum)
                && Objects.equals(author, that.author)
                && status == that.status
                && Objects.equals(approvedBy, that.approvedBy)
                && Objects.equals(rollbackTo, that.rollbackTo);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(ruleName, version, source, checksum, author, status,
                createdAtMs, approvedBy, rollbackTo);
        return 31 * result + Arrays.hashCode(bytecode);
    }

    @Override
    public String toString() {
        return "RuleVersion[" + ruleName + " v" + version + " " + status
                + (approvedBy == null ? "" : " by " + approvedBy) + "]";
    }
}