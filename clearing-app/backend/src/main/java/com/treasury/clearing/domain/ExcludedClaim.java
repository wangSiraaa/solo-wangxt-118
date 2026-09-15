package com.treasury.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 批次内被排除的债权及原因。原始债权仍保留在 receivable 表，完全不受影响。
 * 原因：PLEDGED（质押）/ DISPUTED（争议）/ NO_AGREEMENT（无互抵协议）/
 *      AGREEMENT_MISMATCH（协议与参与方不匹配）/ NOT_ACTIVE（已被确认批次清偿或作废）。
 */
@Entity
@Table(name = "excluded_claim")
public class ExcludedClaim {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id")
    private ClearingBatch batch;

    @Column(name = "receivable_id", nullable = false, length = 40)
    private String receivableId;

    @Column(name = "invoice_no", nullable = false, length = 40)
    private String invoiceNo;

    @Column(name = "exclusion_reason", nullable = false, length = 32)
    private String exclusionReason;

    @Column(length = 256)
    private String detail;

    protected ExcludedClaim() {
    }

    public ExcludedClaim(String id, ClearingBatch batch, String receivableId, String invoiceNo,
                         String exclusionReason, String detail) {
        this.id = id;
        this.batch = batch;
        this.receivableId = receivableId;
        this.invoiceNo = invoiceNo;
        this.exclusionReason = exclusionReason;
        this.detail = detail;
    }

    public String getId() {
        return id;
    }

    public String getReceivableId() {
        return receivableId;
    }

    public String getInvoiceNo() {
        return invoiceNo;
    }

    public String getExclusionReason() {
        return exclusionReason;
    }

    public String getDetail() {
        return detail;
    }
}
