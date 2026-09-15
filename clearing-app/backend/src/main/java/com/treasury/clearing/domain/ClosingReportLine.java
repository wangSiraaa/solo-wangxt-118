package com.treasury.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 关账汇总行：按 (结算日, 互抵协议, 清算币种, 法人) 聚合。
 * 数字含义：netDelta 为该主体当日净债权变化；grossReceivable/grossPayable 为毛应收/应付；
 * cashAmount 为该主体当日应实际收付的现金类指令净额（方向由 from/to 在贡献明细中表达）。
 */
@Entity
@Table(name = "closing_report_line")
public class ClosingReportLine {

    @Id
    @Column(length = 48)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id")
    private ClosingReport report;

    @Column(name = "agreement_code", nullable = false, length = 32)
    private String agreementCode;

    @Column(name = "clearing_currency", nullable = false, length = 3)
    private String clearingCurrency;

    @Column(name = "entity_code", nullable = false, length = 32)
    private String entityCode;

    @Column(name = "net_position", nullable = false, precision = 20, scale = 6)
    private BigDecimal netPosition;

    @Column(name = "gross_receivable", nullable = false, precision = 20, scale = 6)
    private BigDecimal grossReceivable;

    @Column(name = "gross_payable", nullable = false, precision = 20, scale = 6)
    private BigDecimal grossPayable;

    /** 现金类指令净额：正=净收，负=净付。 */
    @Column(name = "cash_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal cashAmount;

    @Column(name = "discharge_count", nullable = false)
    private int dischargeCount;

    protected ClosingReportLine() {
    }

    public ClosingReportLine(String id, ClosingReport report, String agreementCode,
                             String clearingCurrency, String entityCode, BigDecimal netPosition,
                             BigDecimal grossReceivable, BigDecimal grossPayable,
                             BigDecimal cashAmount, int dischargeCount) {
        this.id = id;
        this.report = report;
        this.agreementCode = agreementCode;
        this.clearingCurrency = clearingCurrency;
        this.entityCode = entityCode;
        this.netPosition = netPosition;
        this.grossReceivable = grossReceivable;
        this.grossPayable = grossPayable;
        this.cashAmount = cashAmount;
        this.dischargeCount = dischargeCount;
    }

    public String getId() { return id; }
    public String getAgreementCode() { return agreementCode; }
    public String getClearingCurrency() { return clearingCurrency; }
    public String getEntityCode() { return entityCode; }
    public BigDecimal getNetPosition() { return netPosition; }
    public BigDecimal getGrossReceivable() { return grossReceivable; }
    public BigDecimal getGrossPayable() { return grossPayable; }
    public BigDecimal getCashAmount() { return cashAmount; }
    public int getDischargeCount() { return dischargeCount; }
}
