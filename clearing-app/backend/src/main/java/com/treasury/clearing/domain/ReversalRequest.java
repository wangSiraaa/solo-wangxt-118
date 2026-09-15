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
 * 撤销申请（分级四眼审批的审计主记录）。
 *
 * <p>所需审批名额 {@code requiredApprovals} 在发起申请时按原确认批次实际清偿额与协议门槛
 * 一次性快照：1 = 一审，2 = 双审。门槛为分组（协议+清算币种）级阈值，取所有组中的最大值。
 *
 * <p>对某条 CONFIRMED 批次最多一条申请（{@code reversal_request.original_batch_id} 唯一）。
 * 审批通过不可变决议 {@link ReversalDecision} 累计；只有 {@code approvalsReceived ==
 * requiredApprovals} 时才在同一事务生成冲正批次、恢复债权、置 PROCESSED。
 */
@Entity
@Table(name = "reversal_request")
public class ReversalRequest {

    @Id
    @Column(length = 40)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "original_batch_id")
    private ClearingBatch originalBatch;

    /** 冲正批次（PROCESSED 后回填，幂等去重依据）。 */
    @Column(name = "reversal_batch_id", length = 40)
    private String reversalBatchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReversalStatus status;

    /** 需要的审批名额：1 一审，2 双审（四眼）。 */
    @Column(name = "required_approvals", nullable = false)
    private int requiredApprovals;

    /** 已收到的通过决议数。 */
    @Column(name = "approvals_received", nullable = false)
    private int approvalsReceived;

    /** 触发最高档门槛的协议（快照，展示用）。 */
    @Column(name = "threshold_agreement", length = 32)
    private String thresholdAgreement;

    /** 门槛金额（快照）。 */
    @Column(name = "threshold_amount", precision = 20, scale = 6)
    private BigDecimal thresholdAmount;

    /** 门槛比较所用的最高组清偿额（快照）。 */
    @Column(name = "gross_cleared_amount", precision = 20, scale = 6)
    private BigDecimal grossClearedAmount;

    @Column(name = "gross_cleared_currency", length = 3)
    private String grossClearedCurrency;

    @Column(length = 512)
    private String reason;

    @Column(name = "requested_by", length = 64)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    /** 最终完成/驳回动作的操作人。 */
    @Column(name = "finalized_by", length = 64)
    private String finalizedBy;

    @Column(name = "processed_at")
    private Instant processedAt;

    /** 本次实际恢复为 ACTIVE 的债权张数。 */
    @Column(name = "restored_count")
    private Integer restoredCount;

    @Column(name = "rejected_by", length = 64)
    private String rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "reject_reason", length = 512)
    private String rejectReason;

    @Version
    private long version;

    protected ReversalRequest() {
    }

    public ReversalRequest(String id, ClearingBatch originalBatch, String reason,
                           String requestedBy, Instant requestedAt,
                           int requiredApprovals, String thresholdAgreement,
                           BigDecimal thresholdAmount, BigDecimal grossClearedAmount,
                           String grossClearedCurrency) {
        this.id = id;
        this.originalBatch = originalBatch;
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.requiredApprovals = requiredApprovals;
        this.approvalsReceived = 0;
        this.status = ReversalStatus.REQUESTED;
        this.thresholdAgreement = thresholdAgreement;
        this.thresholdAmount = thresholdAmount;
        this.grossClearedAmount = grossClearedAmount;
        this.grossClearedCurrency = grossClearedCurrency;
    }

    public String getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public ClearingBatch getOriginalBatch() {
        return originalBatch;
    }

    public String getOriginalBatchId() {
        return originalBatch.getId();
    }

    public String getReversalBatchId() {
        return reversalBatchId;
    }

    public ReversalStatus getStatus() {
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

    public BigDecimal getGrossClearedAmount() {
        return grossClearedAmount;
    }

    public String getGrossClearedCurrency() {
        return grossClearedCurrency;
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

    public Integer getRestoredCount() {
        return restoredCount;
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

    /** 记录一条通过决议后的新状态；返回是否已凑满名额（调用方据此触发冲正）。 */
    public boolean recordApproval() {
        if (this.status == ReversalStatus.PROCESSED || this.status == ReversalStatus.REJECTED) {
            throw new ConflictException("撤销申请已终态（" + this.status + "），不能再审批");
        }
        this.approvalsReceived++;
        this.status = approvalsReceived >= requiredApprovals
                ? ReversalStatus.PROCESSED
                : ReversalStatus.PARTIALLY_APPROVED;
        return this.status == ReversalStatus.PROCESSED;
    }

    public void markProcessed(String reversalBatchId, int restoredCount, String by, Instant at) {
        // 末审决议本身也是一票：在已有计数上补齐到所需名额（不依赖外部先 recordApproval）。
        if (this.approvalsReceived < this.requiredApprovals) {
            this.approvalsReceived = this.requiredApprovals;
        }
        this.status = ReversalStatus.PROCESSED;
        this.reversalBatchId = reversalBatchId;
        this.restoredCount = restoredCount;
        this.processedAt = at;
        this.finalizedBy = by;
    }

    public void reject(String by, Instant at, String reason) {
        if (this.status == ReversalStatus.PROCESSED || this.status == ReversalStatus.REJECTED) {
            throw new ConflictException("撤销申请已终态（" + this.status + "），不能驳回");
        }
        this.status = ReversalStatus.REJECTED;
        this.rejectedBy = by;
        this.rejectedAt = at;
        this.rejectReason = reason;
        this.finalizedBy = by;
    }
}
