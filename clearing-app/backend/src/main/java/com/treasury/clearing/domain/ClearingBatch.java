package com.treasury.clearing.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 清算批次。一次试算/确认/冲正的完整快照：
 * 组、付款/互抵指令、逐张发票的清偿分配、尾差归属、被排除债权全部留痕。
 *
 * <p>撤销（冲正）不修改已确认批次的任何金额，只推进状态机并新增一条 {@link BatchKind#REVERSAL}
 * 冲正批次；{@code version} 提供乐观锁，防止重复撤销与并发操作。
 */
@Entity
@Table(name = "clearing_batch")
public class ClearingBatch {

    @Id
    @Column(length = 40)
    private String id;

    /** 乐观锁：撤销申请/审批并发时由 JPA 抛出 ObjectOptimisticLockingFailureException → 409。 */
    @Version
    private long version;

    @Column(nullable = false, length = 128)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BatchStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BatchKind kind;

    /** 冲正批次：指向被冲正的原确认批次；普通批次为 null。 */
    @Column(name = "reverses_batch_id", length = 40)
    private String reversesBatchId;

    /** 原确认批次：指向其冲正批次；未冲正为 null。 */
    @Column(name = "reversal_batch_id", length = 40)
    private String reversalBatchId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 试算/冲正采用的估值/汇率时点。冲正批次沿用原批次，保证账务依据一致可复现。 */
    @Column(name = "valuation_time", nullable = false)
    private Instant valuationTime;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    /** 进入撤销申请（REVERSAL_PENDING）的时间。 */
    @Column(name = "reversal_requested_at")
    private Instant reversalRequestedAt;

    /** 原始债权笔数。 */
    @Column(name = "original_claim_count", nullable = false)
    private int originalClaimCount;

    /** 清算后执行指令笔数。 */
    @Column(name = "resulting_entry_count", nullable = false)
    private int resultingEntryCount;

    /** 被排除的债权笔数（质押/争议/无协议等）。 */
    @Column(name = "excluded_count", nullable = false)
    private int excludedCount;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<ClearingGroup> groups = new ArrayList<>();

    protected ClearingBatch() {
    }

    public ClearingBatch(String id, String label, BatchStatus status, Instant createdAt,
                         Instant valuationTime,
                         int originalClaimCount, int resultingEntryCount, int excludedCount,
                         String createdBy) {
        this.id = id;
        this.label = label;
        this.status = status;
        this.kind = BatchKind.NETTING;
        this.createdAt = createdAt;
        this.valuationTime = valuationTime;
        this.originalClaimCount = originalClaimCount;
        this.resultingEntryCount = resultingEntryCount;
        this.excludedCount = excludedCount;
        this.createdBy = createdBy;
    }

    /** 冲正批次专用构造（kind=REVERSAL，CONFIRMED 即生效，沿用原估值时点）。 */
    public static ClearingBatch reversal(String id, String label, Instant createdAt,
                                         Instant valuationTime, int originalClaimCount,
                                         int resultingEntryCount, int excludedCount,
                                         String createdBy, String reversesBatchId) {
        ClearingBatch b = new ClearingBatch(id, label, BatchStatus.CONFIRMED, createdAt,
                valuationTime, originalClaimCount, resultingEntryCount, excludedCount,
                createdBy);
        b.kind = BatchKind.REVERSAL;
        b.confirmedAt = createdAt;
        b.reversesBatchId = reversesBatchId;
        return b;
    }

    public String getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public String getLabel() {
        return label;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public BatchKind getKind() {
        return kind;
    }

    public String getReversesBatchId() {
        return reversesBatchId;
    }

    public String getReversalBatchId() {
        return reversalBatchId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getValuationTime() {
        return valuationTime;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public Instant getReversalRequestedAt() {
        return reversalRequestedAt;
    }

    public int getOriginalClaimCount() {
        return originalClaimCount;
    }

    public int getResultingEntryCount() {
        return resultingEntryCount;
    }

    public int getExcludedCount() {
        return excludedCount;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public List<ClearingGroup> getGroups() {
        return groups;
    }

    public void addGroup(ClearingGroup group) {
        groups.add(group);
    }

    public void confirm(Instant at) {
        this.status = BatchStatus.CONFIRMED;
        this.confirmedAt = at;
    }

    /** 仅允许从 CONFIRMED 进入撤销审批中。 */
    public void markReversalPending(Instant at) {
        if (this.status != BatchStatus.CONFIRMED) {
            throw new ConflictException("只有 CONFIRMED 批次可申请撤销，当前状态: " + this.status);
        }
        if (this.kind == BatchKind.REVERSAL) {
            throw new ConflictException("冲正批次不可再撤销");
        }
        this.status = BatchStatus.REVERSAL_PENDING;
        this.reversalRequestedAt = at;
    }

    /** 审批驳回：回到 CONFIRMED。 */
    public void markReversalRejected() {
        if (this.status != BatchStatus.REVERSAL_PENDING) {
            throw new ConflictException("只有 REVERSAL_PENDING 批次可驳回，当前状态: " + this.status);
        }
        this.status = BatchStatus.CONFIRMED;
    }

    /** 冲正完成：原批次进入终态 REVERSED 并登记冲正批次。 */
    public void markReversed(Instant at, String reversalBatchId) {
        if (this.status != BatchStatus.REVERSAL_PENDING) {
            throw new ConflictException("只有 REVERSAL_PENDING 批次可冲正，当前状态: " + this.status);
        }
        this.status = BatchStatus.REVERSED;
        this.reversedAt = at;
        this.reversalBatchId = reversalBatchId;
    }
}
