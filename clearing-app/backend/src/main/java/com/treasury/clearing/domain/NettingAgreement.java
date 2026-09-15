package com.treasury.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 互抵协议边界。
 * 同一协议覆盖的法人之间才允许互抵；{@code crossCurrency=false} 时仅允许同币种互抵。
 */
@Entity
@Table(name = "netting_agreement")
public class NettingAgreement {

    @Id
    @Column(length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    /** 是否允许协议内跨币种互抵。 */
    @Column(name = "cross_currency", nullable = false)
    private boolean crossCurrency;

    /** 跨币种清算的结算币种（crossCurrency=true 时使用，如 USD）。 */
    @Column(name = "settlement_currency", length = 3)
    private String settlementCurrency;

    /** 跨币种尾差由哪个法人吸收（协议约定的尾差归属方）。 */
    @Column(name = "rounding_party", length = 32)
    private String roundingParty;

    protected NettingAgreement() {
    }

    public NettingAgreement(String code, String name, boolean crossCurrency,
                            String settlementCurrency, String roundingParty) {
        this.code = code;
        this.name = name;
        this.crossCurrency = crossCurrency;
        this.settlementCurrency = settlementCurrency;
        this.roundingParty = roundingParty;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isCrossCurrency() {
        return crossCurrency;
    }

    public String getSettlementCurrency() {
        return settlementCurrency;
    }

    public String getRoundingParty() {
        return roundingParty;
    }
}
