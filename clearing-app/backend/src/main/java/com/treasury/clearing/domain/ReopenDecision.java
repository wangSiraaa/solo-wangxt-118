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

/** 再开账不可修改审批决议（四眼）。同一申请下审批人唯一，申请人不得审批。 */
@Entity
@Table(name = "reopen_decision", indexes = {
        @Index(name = "ix_reopen_decision_request", columnList = "request_id"),
        @Index(name = "uq_reopen_decision_approver", columnList = "request_id,approver", unique = true)
})
public class ReopenDecision {

    public enum Outcome { APPROVE, REJECT }

    @Id
    @Column(length = 48)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id")
    private ReopenRequest request;

    @Column(nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Outcome outcome;

    @Column(nullable = false, length = 64)
    private String approver;

    @Column(length = 512)
    private String comment;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_before", nullable = false, length = 20)
    private ReopenStatus statusBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_after", nullable = false, length = 20)
    private ReopenStatus statusAfter;

    /** 关账前置状态（CLOSED/REOPEN_PENDING），便于审计关账前后状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "closing_status_before", length = 20)
    private ClosingStatus closingStatusBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "closing_status_after", length = 20)
    private ClosingStatus closingStatusAfter;

    @Column(name = "new_report_id", length = 40)
    private String newReportId;

    @Version
    private long version;

    protected ReopenDecision() {
    }

    public ReopenDecision(String id, ReopenRequest request, int seq, Outcome outcome,
                          String approver, String comment, Instant decidedAt,
                          ReopenStatus statusBefore, ReopenStatus statusAfter,
                          ClosingStatus closingStatusBefore, ClosingStatus closingStatusAfter,
                          String newReportId) {
        this.id = id;
        this.request = request;
        this.seq = seq;
        this.outcome = outcome;
        this.approver = approver;
        this.comment = comment;
        this.decidedAt = decidedAt;
        this.statusBefore = statusBefore;
        this.statusAfter = statusAfter;
        this.closingStatusBefore = closingStatusBefore;
        this.closingStatusAfter = closingStatusAfter;
        this.newReportId = newReportId;
    }

    public String getId() { return id; }
    public int getSeq() { return seq; }
    public Outcome getOutcome() { return outcome; }
    public String getApprover() { return approver; }
    public String getComment() { return comment; }
    public Instant getDecidedAt() { return decidedAt; }
    public ReopenStatus getStatusBefore() { return statusBefore; }
    public ReopenStatus getStatusAfter() { return statusAfter; }
    public ClosingStatus getClosingStatusBefore() { return closingStatusBefore; }
    public ClosingStatus getClosingStatusAfter() { return closingStatusAfter; }
    public String getNewReportId() { return newReportId; }
}
