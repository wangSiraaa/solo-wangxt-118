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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 清算批次。一次试算或确认的完整快照：
 * 组、付款/互抵指令、逐张发票的清偿分配、尾差归属、被排除债权全部留痕。
 */
@Entity
@Table(name = "clearing_batch")
public class ClearingBatch {

    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, length = 128)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BatchStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 试算采用的估值/汇率时点，确认时沿用，保证方案可复现。 */
    @Column(name = "valuation_time", nullable = false)
    private Instant valuationTime;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /** 试算前的原始债权笔数。 */
    @Column(name = "original_claim_count", nullable = false)
    private int originalClaimCount;

    /** 试算后仍需执行的指令笔数（互抵 + 模拟付款 + 尾差调整）。 */
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
        this.createdAt = createdAt;
        this.valuationTime = valuationTime;
        this.originalClaimCount = originalClaimCount;
        this.resultingEntryCount = resultingEntryCount;
        this.excludedCount = excludedCount;
        this.createdBy = createdBy;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public BatchStatus getStatus() {
        return status;
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
}
