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
 * 清算指令：互抵（SETOFF，金额为 0 的账面对冲）或模拟净付款（NET_PAYMENT）。
 * 本系统不接入真实银行，NET_PAYMENT 仅生成台账指令。
 */
@Entity
@Table(name = "clearing_entry")
public class ClearingEntry {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id")
    private ClearingGroup group;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentType type;

    @Column(name = "from_entity", nullable = false, length = 32)
    private String fromEntity;

    @Column(name = "to_entity", nullable = false, length = 32)
    private String toEntity;

    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** 解释该指令由哪些发票互抵形成，便于资金人员追溯。 */
    @Column(name = "description", length = 512)
    private String description;

    protected ClearingEntry() {
    }

    public ClearingEntry(String id, ClearingGroup group, PaymentType type,
                         String fromEntity, String toEntity, BigDecimal amount,
                         String currency, String description) {
        this.id = id;
        this.group = group;
        this.type = type;
        this.fromEntity = fromEntity;
        this.toEntity = toEntity;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public PaymentType getType() {
        return type;
    }

    public String getFromEntity() {
        return fromEntity;
    }

    public String getToEntity() {
        return toEntity;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }
}
