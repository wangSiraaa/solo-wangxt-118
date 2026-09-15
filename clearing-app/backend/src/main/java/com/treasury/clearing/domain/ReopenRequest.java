package com.treasury.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 再开账申请（四眼审批，不可变审计）。
 * 一个结算日在其有效关账存在期间最多一条申请；门槛按当日实际清偿金额与协议阈值快照。
 */
@Entity
@Table(name = "reopen_request")
public class ReopenRequest {

    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    /** 发起再开账时所针对的（当前有效）关账快照版本。 */
    @Column(name = "from_report_version", nullable = false)
    private int fromReportVersion;

    /** 再开账完成后生成的新报表版本（PROCESSED 后回填）。 */
    @Column(name = "new_report_version")
    private Integer newReportVersion;

    @Column(name = "new_report_id", length = 40)
    private String newReportId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReopenStatus status;

    @Column(name = "required_approvals", nullable = false)
    private int requiredApprovals;

    @Column(name = "approvals_received", nullable = false)
    private int approvalsReceived;

    @Column(name = "threshold_agreement", length = 32)
    private String thresholdAgreement;

    @Column(name = "threshold_amount", precision = 20, scale = 6)
    private BigDecimal thresholdAmount;

    /** 门槛比较口径：当日现金类清偿绝对额（跨币种取最大组）。 */
    @Column(name = "day_cash_amount", precision = 20, scale = 6)
    private BigDecimal dayCashAmount;

    @Column(name = "day_cash_currency", length = 3)
    private String dayCashCurrency;

    @Column(length = 512)
    private String reason;

    @Column(name = "requested_by", length = 64)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "finalized_by", length = 64)
    private String finalizedBy;

    @Column(name = "processed_at")
    private Instant processedAt;

    /** 本次再开账纳入/受影响批次数。 */
    @Column(name = "affected_batch_count")
    private Integer affectedBatchCount;

    @Column(name = "rejected_by", length = 64)
    private String rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "reject_reason", length = 512)
    private String rejectReason;

    @Version
    private long version;

    protected ReopenRequest() {
    }

    public ReopenRequest(String id, LocalDate settlementDate, int fromReportVersion,
                         String reason, String requestedBy, Instant requestedAt,
                         int requiredApprovals, String thresholdAgreement,
                         BigDecimal thresholdAmount, BigDecimal dayCashAmount,
                         String dayCashCurrency) {
        this.id = id;
        this.settlementDate = settlementDate;
        this.fromReportVersion = fromReportVersion;
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.requiredApprovals = requiredApprovals;
        this.approvalsReceived = 0;
        this.status = ReopenStatus.REQUESTED;
        this.thresholdAgreement = thresholdAgreement;
        this.thresholdAmount = thresholdAmount;
        this.dayCashAmount = dayCashAmount;
        this.dayCashCurrency = dayCashCurrency;
    }

    public String getId() { return id; }
    public LocalDate getSettlementDate() { return settlementDate; }
    public int getFromReportVersion() { return fromReportVersion; }
    public Integer getNewReportVersion() { return newReportVersion; }
    public String getNewReportId() { return newReportId; }
    public ReopenStatus getStatus() { return status; }
    public int getRequiredApprovals() { return requiredApprovals; }
    public int getApprovalsReceived() { return approvalsReceived; }
    public String getThresholdAgreement() { return thresholdAgreement; }
    public BigDecimal getThresholdAmount() { return thresholdAmount; }
    public BigDecimal getDayCashAmount() { return dayCashAmount; }
    public String getDayCashCurrency() { return dayCashCurrency; }
    public String getReason() { return reason; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public String getFinalizedBy() { return finalizedBy; }
    public Instant getProcessedAt() { return processedAt; }
    public Integer getAffectedBatchCount() { return affectedBatchCount; }
    public String getRejectedBy() { return rejectedBy; }
    public Instant getRejectedAt() { return rejectedAt; }
    public String getRejectReason() { return rejectReason; }

    public void recordApproval() {
        if (this.status == ReopenStatus.PROCESSED || this.status == ReopenStatus.REJECTED) {
            throw new ConflictException("再开账申请已终态（" + this.status + "）");
        }
        this.approvalsReceived++;
        this.status = approvalsReceived >= requiredApprovals
                ? ReopenStatus.PROCESSED : ReopenStatus.PARTIALLY_APPROVED;
    }

    public void markProcessed(String newReportId, int newVersion, int affectedBatches,
                              String by, Instant at) {
        if (approvalsReceived < requiredApprovals) {
            approvalsReceived = requiredApprovals;
        }
        this.status = ReopenStatus.PROCESSED;
        this.newReportId = newReportId;
        this.newReportVersion = newVersion;
        this.affectedBatchCount = affectedBatches;
        this.finalizedBy = by;
        this.processedAt = at;
    }

    public void reject(String by, Instant at, String reason) {
        if (this.status == ReopenStatus.PROCESSED || this.status == ReopenStatus.REJECTED) {
            throw new ConflictException("再开账申请已终态（" + this.status + "）");
        }
        this.status = ReopenStatus.REJECTED;
        this.rejectedBy = by;
        this.rejectedAt = at;
        this.rejectReason = reason;
        this.finalizedBy = by;
    }
}
