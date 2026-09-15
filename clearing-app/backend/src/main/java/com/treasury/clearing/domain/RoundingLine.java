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
 * 跨币种尾差明细。两类：
 * 1) FX_CONVERSION：逐张发票按汇率换算并按币种最小单位取整产生的尾差（归属该发票债权人）；
 * 2) BEARER_ADJUST：把所有换算尾差一次性归集到协议约定尾差承担方的平衡调整行。
 * 净头寸只按换算后的整数金额轧差，尾差通过 BEARER_ADJUST 单独归属，
 * 从而保证任何法人的清算币种余额都不会被尾差破坏。
 */
@Entity
@Table(name = "rounding_line")
public class RoundingLine {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id")
    private ClearingGroup group;

    /** FX_CONVERSION / BEARER_ADJUST */
    @Column(name = "line_type", nullable = false, length = 20)
    private String lineType;

    @Column(name = "entity_code", nullable = false, length = 32)
    private String entityCode;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** 尾差金额（清算币种），正数表示该主体应收尾差补偿，负数表示承担。 */
    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal amount;

    @Column(name = "ref_invoice_no", length = 40)
    private String refInvoiceNo;

    @Column(name = "fx_rate", precision = 20, scale = 10)
    private BigDecimal fxRate;

    /** 汇率时点，如 2026-09-15T09:30:00Z（资金部维护的报价时点）。 */
    @Column(name = "rate_time", length = 40)
    private String rateTime;

    @Column(length = 256)
    private String note;

    protected RoundingLine() {
    }

    public static RoundingLine fx(String id, ClearingGroup group, String entityCode, String currency,
                                  BigDecimal amount, String refInvoiceNo, BigDecimal fxRate,
                                  String rateTime, String note) {
        RoundingLine r = new RoundingLine();
        r.id = id;
        r.group = group;
        r.lineType = "FX_CONVERSION";
        r.entityCode = entityCode;
        r.currency = currency;
        r.amount = amount;
        r.refInvoiceNo = refInvoiceNo;
        r.fxRate = fxRate;
        r.rateTime = rateTime;
        r.note = note;
        return r;
    }

    public static RoundingLine bearer(String id, ClearingGroup group, String entityCode,
                                      String currency, BigDecimal amount, String note) {
        RoundingLine r = new RoundingLine();
        r.id = id;
        r.group = group;
        r.lineType = "BEARER_ADJUST";
        r.entityCode = entityCode;
        r.currency = currency;
        r.amount = amount;
        r.note = note;
        return r;
    }

    public String getId() {
        return id;
    }

    public String getLineType() {
        return lineType;
    }

    public String getEntityCode() {
        return entityCode;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getRefInvoiceNo() {
        return refInvoiceNo;
    }

    public BigDecimal getFxRate() {
        return fxRate;
    }

    public String getRateTime() {
        return rateTime;
    }

    public String getNote() {
        return note;
    }
}
