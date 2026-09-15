package com.treasury.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 资金部维护的汇率快照（手工录入的内部记账汇率，不接外部行情/银行）。
 * 批次试算时按 (from_currency, to_currency) 取 rate_time 不晚于试算时点的最新一条。
 */
@Entity
@Table(name = "fx_rate")
public class FxRate {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "from_currency", nullable = false, length = 3)
    private String fromCurrency;

    @Column(name = "to_currency", nullable = false, length = 3)
    private String toCurrency;

    @Column(nullable = false, precision = 20, scale = 10)
    private BigDecimal rate;

    @Column(name = "rate_time", nullable = false)
    private Instant rateTime;

    @Column(length = 64)
    private String source;

    protected FxRate() {
    }

    public FxRate(String id, String fromCurrency, String toCurrency, BigDecimal rate,
                  Instant rateTime, String source) {
        this.id = id;
        this.fromCurrency = fromCurrency;
        this.toCurrency = toCurrency;
        this.rate = rate;
        this.rateTime = rateTime;
        this.source = source;
    }

    public String getId() {
        return id;
    }

    public String getFromCurrency() {
        return fromCurrency;
    }

    public String getToCurrency() {
        return toCurrency;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public Instant getRateTime() {
        return rateTime;
    }

    public String getSource() {
        return source;
    }
}
