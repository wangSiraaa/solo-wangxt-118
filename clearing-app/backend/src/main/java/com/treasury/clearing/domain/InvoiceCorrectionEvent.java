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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 不可修改的发票更正事件。
 *
 * <p>永久记录被更正的原始债权、旧值/新值（金额与协议归属）、原因、申请人、时间及生效范围。
 * 原始 {@link Receivable} 金额与状态不变；更正只在差额批次中产生净额差异指令。
 * 同一差额申请内发票唯一（DB 唯一索引），杜绝同一发票重复更正。
 */
@Entity
@Table(name = "invoice_correction_event", indexes = {
        @Index(name = "ix_corr_request", columnList = "request_id"),
        @Index(name = "uq_corr_request_receivable", columnList = "request_id,receivable_id", unique = true)
})
public class InvoiceCorrectionEvent {

    public enum Field { AMOUNT, AGREEMENT }

    @Id
    @Column(length = 48)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id")
    private AdjustmentRequest request;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "receivable_id", nullable = false, length = 40)
    private String receivableId;

    @Column(name = "invoice_no", nullable = false, length = 40)
    private String invoiceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "corrected_field", nullable = false, length = 10)
    private Field correctedField;

    // 旧值（原始发票）
    @Column(name = "old_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal oldAmount;
    @Column(name = "old_currency", nullable = false, length = 3)
    private String oldCurrency;
    @Column(name = "old_agreement_code", length = 32)
    private String oldAgreementCode;

    // 新值（更正后；币种不可改，currency 始终沿用原币种）
    @Column(name = "new_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal newAmount;
    @Column(name = "new_currency", nullable = false, length = 3)
    private String newCurrency;
    @Column(name = "new_agreement_code", length = 32)
    private String newAgreementCode;

    /** 生效范围说明，如“自 2026-09 结算周期起”。 */
    @Column(name = "effective_scope", length = 256)
    private String effectiveScope;

    @Column(length = 512)
    private String reason;

    @Column(name = "requested_by", length = 64)
    private String requestedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // 预计算差额（按原估值时点、目标清算币种口径），供差额引擎直接使用
    @Column(name = "old_converted", nullable = false, precision = 20, scale = 6)
    private BigDecimal oldConverted;
    @Column(name = "new_converted", nullable = false, precision = 20, scale = 6)
    private BigDecimal newConverted;
    @Column(name = "delta_converted", nullable = false, precision = 20, scale = 6)
    private BigDecimal deltaConverted;
    @Column(name = "clearing_currency", nullable = false, length = 3)
    private String clearingCurrency;

    @Version
    private long version;

    protected InvoiceCorrectionEvent() {
    }

    public InvoiceCorrectionEvent(String id, AdjustmentRequest request, int seq, String receivableId,
                                  String invoiceNo, Field correctedField,
                                  BigDecimal oldAmount, String oldCurrency, String oldAgreementCode,
                                  BigDecimal newAmount, String newCurrency, String newAgreementCode,
                                  String effectiveScope, String reason, String requestedBy,
                                  Instant createdAt,
                                  BigDecimal oldConverted, BigDecimal newConverted,
                                  String clearingCurrency) {
        this.id = id;
        this.request = request;
        this.seq = seq;
        this.receivableId = receivableId;
        this.invoiceNo = invoiceNo;
        this.correctedField = correctedField;
        this.oldAmount = oldAmount;
        this.oldCurrency = oldCurrency;
        this.oldAgreementCode = oldAgreementCode;
        this.newAmount = newAmount;
        this.newCurrency = newCurrency;
        this.newAgreementCode = newAgreementCode;
        this.effectiveScope = effectiveScope;
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.createdAt = createdAt;
        this.oldConverted = oldConverted;
        this.newConverted = newConverted;
        this.deltaConverted = newConverted.subtract(oldConverted);
        this.clearingCurrency = clearingCurrency;
    }

    public String getId() { return id; }
    public int getSeq() { return seq; }
    public String getReceivableId() { return receivableId; }
    public String getInvoiceNo() { return invoiceNo; }
    public Field getCorrectedField() { return correctedField; }
    public BigDecimal getOldAmount() { return oldAmount; }
    public String getOldCurrency() { return oldCurrency; }
    public String getOldAgreementCode() { return oldAgreementCode; }
    public BigDecimal getNewAmount() { return newAmount; }
    public String getNewCurrency() { return newCurrency; }
    public String getNewAgreementCode() { return newAgreementCode; }
    public String getEffectiveScope() { return effectiveScope; }
    public String getReason() { return reason; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public BigDecimal getOldConverted() { return oldConverted; }
    public BigDecimal getNewConverted() { return newConverted; }
    public BigDecimal getDeltaConverted() { return deltaConverted; }
    public String getClearingCurrency() { return clearingCurrency; }
}
