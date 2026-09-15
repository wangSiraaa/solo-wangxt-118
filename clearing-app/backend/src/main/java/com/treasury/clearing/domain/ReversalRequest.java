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

import java.time.Instant;

/**
 * 撤销申请（审计主记录）。
 *
 * <p>对某条 CONFIRMED 批次最多存在一条申请：{@code reversal_request} 上对
 * {@code original_batch_id} 有唯一约束，配合批次乐观锁，保证重复申请/并发只能成功一次。
 * 申请、审批、冲正批次生成与债权恢复的操作人与时间点全部留痕，且不可删除。
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

    /** 冲正批次（PROCESSED 后回填，幂等处理的去重依据）。 */
    @Column(name = "reversal_batch_id", length = 40)
    private String reversalBatchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReversalStatus status;

    @Column(length = 512)
    private String reason;

    @Column(name = "requested_by", length = 64)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "approved_by", length = 64)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** 冲正处理完成时间（与 approvedAt 同事务，单列以便语义清晰）。 */
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

    public ReversalRequest(String id, ClearingBatch originalBatch, ReversalStatus status,
                           String reason, String requestedBy, Instant requestedAt) {
        this.id = id;
        this.originalBatch = originalBatch;
        this.status = status;
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
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

    public String getReason() {
        return reason;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
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

    public void approve(String by, Instant at) {
        if (this.status != ReversalStatus.REQUESTED) {
            throw new ConflictException("撤销申请已处理，当前状态: " + this.status);
        }
        this.approvedBy = by;
        this.approvedAt = at;
    }

    public void markProcessed(String reversalBatchId, int restoredCount, Instant at) {
        this.status = ReversalStatus.PROCESSED;
        this.reversalBatchId = reversalBatchId;
        this.restoredCount = restoredCount;
        this.processedAt = at;
    }

    public void reject(String by, Instant at, String reason) {
        if (this.status != ReversalStatus.REQUESTED) {
            throw new ConflictException("撤销申请已处理，当前状态: " + this.status);
        }
        this.status = ReversalStatus.REJECTED;
        this.rejectedBy = by;
        this.rejectedAt = at;
        this.rejectReason = reason;
    }
}
