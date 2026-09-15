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

/**
 * 差额更正申请（审计主记录，分级四眼审批）。
 *
 * <p>一条原确认批次最多一条更正申请（{@code original_batch_id} 唯一）。申请本身不改写原批次
 * 与原始债权；只有名额凑满时才在同事务生成唯一 {@code ADJUSTMENT} 差额批次。
 */
@Entity
@Table(name = "adjustment_request")
public class AdjustmentRequest {

    @Id
    @Column(length = 40)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "original_batch_id")
    private ClearingBatch originalBatch;

    @Column(name = "adjustment_batch_id", length = 40)
    private String adjustmentBatchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdjustmentStatus status;

    @Column(name = "required_approvals", nullable = false)
    private int requiredApprovals;

    @Column(name = "approvals_received", nullable = false)
    private int approvalsReceived;

    /** 触发最高档门槛的协议（快照）。 */
    @Column(name = "threshold_agreement", length = 32)
    private String thresholdAgreement;

    @Column(name = "threshold_amount", precision = 20, scale = 6)
    private BigDecimal thresholdAmount;

    /** 本次更正涉及的绝对差额合计（清算币种，门槛比较口径，快照）。 */
    @Column(name = "delta_gross_amount", precision = 20, scale = 6)
    private BigDecimal deltaGrossAmount;

    @Column(name = "delta_gross_currency", length = 3)
    private String deltaGrossCurrency;

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

    @Column(name = "event_count")
    private Integer eventCount;

    @Column(name = "rejected_by", length = 64)
    private String rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "reject_reason", length = 512)
    private String rejectReason;

    @Version
    private long version;

    protected AdjustmentRequest() {
    }

    public AdjustmentRequest(String id, ClearingBatch originalBatch, String reason,
                             String requestedBy, Instant requestedAt, int requiredApprovals,
                             String thresholdAgreement, BigDecimal thresholdAmount,
                             BigDecimal deltaGrossAmount, String deltaGrossCurrency) {
        this.id = id;
        this.originalBatch = originalBatch;
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.requiredApprovals = requiredApprovals;
        this.approvalsReceived = 0;
        this.status = AdjustmentStatus.REQUESTED;
        this.thresholdAgreement = thresholdAgreement;
        this.thresholdAmount = thresholdAmount;
        this.deltaGrossAmount = deltaGrossAmount;
        this.deltaGrossCurrency = deltaGrossCurrency;
    }

    public String getId() {
        return id;
    }

    public String getOriginalBatchId() {
        return originalBatch.getId();
    }

    public String getAdjustmentBatchId() {
        return adjustmentBatchId;
    }

    public AdjustmentStatus getStatus() {
        return status;
    }

    public int getRequiredApprovals() {
        return requiredApprovals;
    }

    public int getApprovalsReceived() {
        return approvalsReceived;
    }

    public String getThresholdAgreement() {
        return thresholdAgreement;
    }

    public BigDecimal getThresholdAmount() {
        return thresholdAmount;
    }

    public BigDecimal getDeltaGrossAmount() {
        return deltaGrossAmount;
    }

    public String getDeltaGrossCurrency() {
        return deltaGrossCurrency;
    }

    public String getReason() {
        return reason;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public String getFinalizedBy() {
        return finalizedBy;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public Integer getEventCount() {
        return eventCount;
    }

    public void setEventCount(Integer eventCount) {
        this.eventCount = eventCount;
    }

    public String getRejectedBy() {
        return rejectedBy;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void recordApproval() {
        if (this.status == AdjustmentStatus.PROCESSED || this.status == AdjustmentStatus.REJECTED) {
            throw new ConflictException("差额更正申请已终态（" + this.status + "），不能再审批");
        }
        this.approvalsReceived++;
        this.status = approvalsReceived >= requiredApprovals
                ? AdjustmentStatus.PROCESSED
                : AdjustmentStatus.PARTIALLY_APPROVED;
    }

    public void markProcessed(String adjustmentBatchId, int eventCount, String by, Instant at) {
        if (this.approvalsReceived < this.requiredApprovals) {
            this.approvalsReceived = this.requiredApprovals;
        }
        this.status = AdjustmentStatus.PROCESSED;
        this.adjustmentBatchId = adjustmentBatchId;
        this.eventCount = eventCount;
        this.processedAt = at;
        this.finalizedBy = by;
    }

    public void reject(String by, Instant at, String reason) {
        if (this.status == AdjustmentStatus.PROCESSED || this.status == AdjustmentStatus.REJECTED) {
            throw new ConflictException("差额更正申请已终态（" + this.status + "），不能驳回");
        }
        this.status = AdjustmentStatus.REJECTED;
        this.rejectedBy = by;
        this.rejectedAt = at;
        this.rejectReason = reason;
        this.finalizedBy = by;
    }
}
