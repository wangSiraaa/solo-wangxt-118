package com.treasury.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 日终结算报表快照（关账）。
 *
 * <p>同一结算日的关账按版本递增：首发版本 1（CLOSED）；再开账审批通过后，旧版本置
 * {@link ClosingStatus#SUPERSEDED}，生成版本 +1 的新 CLOSED 快照，历史永不修改/删除。
 * 关账与版本化均以应用行锁 + 唯一约束保证唯一；{@code (settlement_date, version)} 唯一。
 */
@Entity
@Table(name = "closing_report", uniqueConstraints = {
        @UniqueConstraint(name = "uq_closing_date_version",
                columnNames = {"settlement_date", "report_version"})
})
public class ClosingReport {

    @Id
    @Column(length = 40)
    private String id;

    @Version
    @Column(name = "version")
    private long versionLock;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    /** 版本号，自 1 递增。 */
    @Column(name = "report_version", nullable = false)
    private int reportVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClosingStatus status;

    @Column(name = "closed_by", length = 64)
    private String closedBy;

    @Column(name = "closed_at", nullable = false)
    private Instant closedAt;

    /** 再开账版本：原因与申请人（首发版本为空）。 */
    @Column(name = "reopen_reason", length = 512)
    private String reopenReason;

    @Column(name = "created_by_reopen", length = 64)
    private String createdByReopen;

    /** 参与汇总的批次数（去重）。 */
    @Column(name = "batch_count", nullable = false)
    private int batchCount;

    /** 现金类（NET_PAYMENT / ADJUSTMENT）指令金额合计，按币种分组在 line 中体现，这里给总额展示。 */
    @Column(name = "total_cash_amount", precision = 20, scale = 6)
    private java.math.BigDecimal totalCashAmount;

    @OneToMany(mappedBy = "report", fetch = FetchType.LAZY,
            cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    private List<ClosingReportLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "report", fetch = FetchType.LAZY,
            cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    private List<ClosingContribution> contributions = new ArrayList<>();

    protected ClosingReport() {
    }

    public ClosingReport(String id, LocalDate settlementDate, int reportVersion,
                         ClosingStatus status, String closedBy, Instant closedAt,
                         String reopenReason, String createdByReopen, int batchCount,
                         java.math.BigDecimal totalCashAmount) {
        this.id = id;
        this.settlementDate = settlementDate;
        this.reportVersion = reportVersion;
        this.status = status;
        this.closedBy = closedBy;
        this.closedAt = closedAt;
        this.reopenReason = reopenReason;
        this.createdByReopen = createdByReopen;
        this.batchCount = batchCount;
        this.totalCashAmount = totalCashAmount;
    }

    public String getId() { return id; }
    public LocalDate getSettlementDate() { return settlementDate; }
    public int getReportVersion() { return reportVersion; }
    public ClosingStatus getStatus() { return status; }
    public String getClosedBy() { return closedBy; }
    public Instant getClosedAt() { return closedAt; }
    public String getReopenReason() { return reopenReason; }
    public String getCreatedByReopen() { return createdByReopen; }
    public int getBatchCount() { return batchCount; }
    public java.math.BigDecimal getTotalCashAmount() { return totalCashAmount; }
    public List<ClosingReportLine> getLines() { return lines; }
    public List<ClosingContribution> getContributions() { return contributions; }

    public void addLine(ClosingReportLine line) { lines.add(line); }
    public void addContribution(ClosingContribution c) { contributions.add(c); }

    /** 再开账审批中：当前 CLOSED 版本转入 REOPEN_PENDING（仍锁定该日）。 */
    public void moveToReopenPending() {
        if (this.status != ClosingStatus.CLOSED) {
            throw new ConflictException("只有 CLOSED 报表可进入再开账审批，当前: " + this.status);
        }
        this.status = ClosingStatus.REOPEN_PENDING;
    }

    /** 再开账驳回：回到 CLOSED。 */
    public void backToClosed() {
        if (this.status != ClosingStatus.REOPEN_PENDING) {
            throw new ConflictException("只有 REOPEN_PENDING 报表可回到 CLOSED，当前: " + this.status);
        }
        this.status = ClosingStatus.CLOSED;
    }

    /** 再开账生效：旧版本被取代（终态），仅允许从 REOPEN_PENDING 转入。 */
    public void supersede() {
        if (this.status != ClosingStatus.REOPEN_PENDING) {
            throw new ConflictException("只有 REOPEN_PENDING 报表可被新版本取代，当前: " + this.status);
        }
        this.status = ClosingStatus.SUPERSEDED;
    }
}
