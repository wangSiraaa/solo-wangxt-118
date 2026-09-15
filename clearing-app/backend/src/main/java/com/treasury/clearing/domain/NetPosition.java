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
 * 法人在某清算组中的净头寸（清算币种口径）。
 * 正数 = 净收款方，负数 = 净付款方，0 = 环形债务全部对冲、无收付。
 */
@Entity
@Table(name = "net_position")
public class NetPosition {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id")
    private ClearingGroup group;

    @Column(name = "entity_code", nullable = false, length = 32)
    private String entityCode;

    @Column(name = "gross_receivable", nullable = false, precision = 20, scale = 6)
    private BigDecimal grossReceivable;

    @Column(name = "gross_payable", nullable = false, precision = 20, scale = 6)
    private BigDecimal grossPayable;

    /** 净头寸 = 应收合计 - 应付合计（清算币种）。 */
    @Column(name = "net_amount", nullable = false, precision = 20, scale = 6)
    private BigDecimal netAmount;

    protected NetPosition() {
    }

    public NetPosition(String id, ClearingGroup group, String entityCode,
                       BigDecimal grossReceivable, BigDecimal grossPayable, BigDecimal netAmount) {
        this.id = id;
        this.group = group;
        this.entityCode = entityCode;
        this.grossReceivable = grossReceivable;
        this.grossPayable = grossPayable;
        this.netAmount = netAmount;
    }

    public String getId() {
        return id;
    }

    public String getEntityCode() {
        return entityCode;
    }

    public BigDecimal getGrossReceivable() {
        return grossReceivable;
    }

    public BigDecimal getGrossPayable() {
        return grossPayable;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }
}
