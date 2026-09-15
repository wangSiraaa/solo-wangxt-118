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
 * 单张原始发票在某清算组中的清偿分配。
 * 每张被纳入清算的发票都有一行；金额分为互抵部分（setoff）与净付款部分（payment），
 * 跨币种时同时记录原始币种金额与换算后的清算币种金额。
 * 资金人员在债务图上点开节点/边即可追到这里，再回到发票号。
 */
@Entity
@Table(name = "invoice_discharge")
public class InvoiceDischarge {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id")
    private ClearingGroup group;

    @Column(name = "receivable_id", nullable = false, length = 40)
    private String receivableId;

    @Column(name = "invoice_no", nullable = false, length = 40)
    private String invoiceNo;

    @Column(name = "creditor_code", nullable = false, length = 32)
    private String creditorCode;

    @Column(name = "debtor_code", nullable = false, length = 32)
    private String debtorCode;

    @Column(name = "original_currency", nullable = false, length = 3)
    private String originalCurrency;

    @Column(name = "original_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal originalAmount;

    /** 该发票折成清算币种的金额（同币种时等于 original_amount）。 */
    @Column(name = "converted_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal convertedAmount;

    /** 通过账面对冲互抵清偿的金额（清算币种）。 */
    @Column(name = "setoff_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal setoffAmount;

    /** 通过模拟净付款清偿的金额（清算币种）。 */
    @Column(name = "payment_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal paymentAmount;

    /** 跨币种时使用的汇率（原始币种 -> 清算币种）；同币种为 1。 */
    @Column(name = "fx_rate", nullable = false, precision = 20, scale = 10)
    private BigDecimal fxRate;

    protected InvoiceDischarge() {
    }

    public InvoiceDischarge(String id, ClearingGroup group, String receivableId, String invoiceNo,
                            String creditorCode, String debtorCode, String originalCurrency,
                            BigDecimal originalAmount, BigDecimal convertedAmount,
                            BigDecimal setoffAmount, BigDecimal paymentAmount, BigDecimal fxRate) {
        this.id = id;
        this.group = group;
        this.receivableId = receivableId;
        this.invoiceNo = invoiceNo;
        this.creditorCode = creditorCode;
        this.debtorCode = debtorCode;
        this.originalCurrency = originalCurrency;
        this.originalAmount = originalAmount;
        this.convertedAmount = convertedAmount;
        this.setoffAmount = setoffAmount;
        this.paymentAmount = paymentAmount;
        this.fxRate = fxRate;
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

    public String getCreditorCode() {
        return creditorCode;
    }

    public String getDebtorCode() {
        return debtorCode;
    }

    public String getOriginalCurrency() {
        return originalCurrency;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public BigDecimal getConvertedAmount() {
        return convertedAmount;
    }

    public BigDecimal getSetoffAmount() {
        return setoffAmount;
    }

    public BigDecimal getPaymentAmount() {
        return paymentAmount;
    }

    public BigDecimal getFxRate() {
        return fxRate;
    }
}
