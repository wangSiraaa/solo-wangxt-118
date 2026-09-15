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

import java.math.BigDecimal;

/**
 * 关账贡献明细：把某个汇总行追溯到原始批次及其指令/发票清偿/差额/冲正贡献。
 * 每个被纳入关账的批次（NETTING/ADJUSTMENT/REVERSAL）在其涉及的每个协议/币种组留一行，
 * 记录批次性质、组级毛应收/应付、净头寸、现金指令额与清偿张数。
 */
@Entity
@Table(name = "closing_contribution")
public class ClosingContribution {

    @Id
    @Column(length = 56)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id")
    private ClosingReport report;

    @Column(name = "batch_id", nullable = false, length = 40)
    private String batchId;

    @Column(name = "batch_kind", nullable = false, length = 16)
    private String batchKind;

    @Column(name = "batch_label", length = 128)
    private String batchLabel;

    @Column(name = "agreement_code", nullable = false, length = 32)
    private String agreementCode;

    @Column(name = "clearing_currency", nullable = false, length = 3)
    private String clearingCurrency;

    @Column(name = "gross_receivable", nullable = false, precision = 20, scale = 6)
    private BigDecimal grossReceivable;

    @Column(name = "gross_payable", nullable = false, precision = 20, scale = 6)
    private BigDecimal grossPayable;

    @Column(name = "net_position", nullable = false, precision = 20, scale = 6)
    private BigDecimal netPosition;

    @Column(name = "cash_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal cashAmount;

    @Column(name = "discharge_count", nullable = false)
    private int dischargeCount;

    /** 贡献方向：NETTING=原始清算，REVERSAL=冲回（负贡献），ADJUSTMENT=差额更正。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "contribution_type", nullable = false, length = 16)
    private BatchKind contributionType;

    protected ClosingContribution() {
    }

    public ClosingContribution(String id, ClosingReport report, String batchId, BatchKind batchKind,
                               String batchLabel, String agreementCode, String clearingCurrency,
                               BigDecimal grossReceivable, BigDecimal grossPayable,
                               BigDecimal netPosition, BigDecimal cashAmount, int dischargeCount) {
        this.id = id;
        this.report = report;
        this.batchId = batchId;
        this.batchKind = batchKind.name();
        this.contributionType = batchKind;
        this.batchLabel = batchLabel;
        this.agreementCode = agreementCode;
        this.clearingCurrency = clearingCurrency;
        this.grossReceivable = grossReceivable;
        this.grossPayable = grossPayable;
        this.netPosition = netPosition;
        this.cashAmount = cashAmount;
        this.dischargeCount = dischargeCount;
    }

    public String getId() { return id; }
    public String getBatchId() { return batchId; }
    public String getBatchKind() { return batchKind; }
    public String getBatchLabel() { return batchLabel; }
    public String getAgreementCode() { return agreementCode; }
    public String getClearingCurrency() { return clearingCurrency; }
    public BigDecimal getGrossReceivable() { return grossReceivable; }
    public BigDecimal getGrossPayable() { return grossPayable; }
    public BigDecimal getNetPosition() { return netPosition; }
    public BigDecimal getCashAmount() { return cashAmount; }
    public int getDischargeCount() { return dischargeCount; }
    public BatchKind getContributionType() { return contributionType; }
}
