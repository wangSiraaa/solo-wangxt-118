package com.treasury.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 差额更正的不可修改审批决议（四眼审计）。每次审批只新增一行；
 * 同一更正申请下审批人唯一，申请人不得审批。末审决议记录差额批次。
 */
@Entity
@Table(name = "adjustment_decision", indexes = {
        @Index(name = "ix_adj_decision_request", columnList = "request_id"),
        @Index(name = "uq_adj_decision_request_approver", columnList = "request_id,approver", unique = true)
})
public class AdjustmentDecision {

    public enum Outcome { APPROVE, REJECT }

    @Id
    @Column(length = 48)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id")
    private AdjustmentRequest request;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Outcome outcome;

    @Column(name = "approver", nullable = false, length = 64)
    private String approver;

    @Column(length = 512)
    private String comment;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_before", nullable = false, length = 20)
    private AdjustmentStatus statusBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_after", nullable = false, length = 20)
    private AdjustmentStatus statusAfter;

    @Column(name = "adjustment_batch_id", length = 40)
    private String adjustmentBatchId;

    @Version
    private long version;

    protected AdjustmentDecision() {
    }

    public AdjustmentDecision(String id, AdjustmentRequest request, int seq, Outcome outcome,
                              String approver, String comment, Instant decidedAt,
                              AdjustmentStatus statusBefore, AdjustmentStatus statusAfter,
                              String adjustmentBatchId) {
        this.id = id;
        this.request = request;
        this.seq = seq;
        this.outcome = outcome;
        this.approver = approver;
        this.comment = comment;
        this.decidedAt = decidedAt;
        this.statusBefore = statusBefore;
        this.statusAfter = statusAfter;
        this.adjustmentBatchId = adjustmentBatchId;
    }

    public String getId() { return id; }
    public int getSeq() { return seq; }
    public Outcome getOutcome() { return outcome; }
    public String getApprover() { return approver; }
    public String getComment() { return comment; }
    public Instant getDecidedAt() { return decidedAt; }
    public AdjustmentStatus getStatusBefore() { return statusBefore; }
    public AdjustmentStatus getStatusAfter() { return statusAfter; }
    public String getAdjustmentBatchId() { return adjustmentBatchId; }
}
