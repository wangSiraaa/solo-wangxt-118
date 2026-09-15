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
 * 不可修改的撤销审批决议（四眼审计）。
 *
 * <p>每次审批动作（通过/驳回）都新增一行，绝不更新或删除。记录审批人、意见、时间，
 * 以及该决议落库<b>前后</b>申请状态，形成完整决议链。
 * 同一撤销申请下，审批人唯一（{@code uq_decision_request_approver}），
 * 且审批人不得等于申请人（服务层强制）。
 */
@Entity
@Table(name = "reversal_decision", indexes = {
        @Index(name = "ix_decision_request", columnList = "request_id"),
        @Index(name = "uq_decision_request_approver", columnList = "request_id,approver", unique = true)
})
public class ReversalDecision {

    @Id
    @Column(length = 48)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id")
    private ReversalRequest request;

    /** 决议序号（同一申请内从 1 递增，表达审批顺序/链）。 */
    @Column(name = "seq", nullable = false)
    private int seq;

    public enum Outcome {
        APPROVE, REJECT
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Outcome outcome;

    @Column(name = "approver", nullable = false, length = 64)
    private String approver;

    @Column(length = 512)
    private String comment;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    /** 决议前申请状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status_before", nullable = false, length = 20)
    private ReversalStatus statusBefore;

    /** 决议后申请状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status_after", nullable = false, length = 20)
    private ReversalStatus statusAfter;

    /** 若该决议触发冲正，记录冲正批次（其余决议为空）。 */
    @Column(name = "reversal_batch_id", length = 40)
    private String reversalBatchId;

    @Version
    private long version;

    protected ReversalDecision() {
    }

    public ReversalDecision(String id, ReversalRequest request, int seq, Outcome outcome,
                            String approver, String comment, Instant decidedAt,
                            ReversalStatus statusBefore, ReversalStatus statusAfter,
                            String reversalBatchId) {
        this.id = id;
        this.request = request;
        this.seq = seq;
        this.outcome = outcome;
        this.approver = approver;
        this.comment = comment;
        this.decidedAt = decidedAt;
        this.statusBefore = statusBefore;
        this.statusAfter = statusAfter;
        this.reversalBatchId = reversalBatchId;
    }

    public String getId() {
        return id;
    }

    public int getSeq() {
        return seq;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getApprover() {
        return approver;
    }

    public String getComment() {
        return comment;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public ReversalStatus getStatusBefore() {
        return statusBefore;
    }

    public ReversalStatus getStatusAfter() {
        return statusAfter;
    }

    public String getReversalBatchId() {
        return reversalBatchId;
    }

    public void setReversalBatchId(String reversalBatchId) {
        this.reversalBatchId = reversalBatchId;
    }
}
