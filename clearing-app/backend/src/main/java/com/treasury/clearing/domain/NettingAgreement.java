package com.treasury.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

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

    /**
     * 撤销该协议下确认方案的一审门槛（按清算币种总清偿额比较）。
     * 组清偿额 ≥ 门槛时需要双审（四眼），否则一审即可；为空表示始终一审。
     */
    @Column(name = "dual_approval_threshold", precision = 20, scale = 6)
    private BigDecimal dualApprovalThreshold;
    protected NettingAgreement() {
    }

    public NettingAgreement(String code, String name, boolean crossCurrency,
                            String settlementCurrency, String roundingParty) {
        this(code, name, crossCurrency, settlementCurrency, roundingParty, null);
    }

    public NettingAgreement(String code, String name, boolean crossCurrency,
                            String settlementCurrency, String roundingParty,
                            BigDecimal dualApprovalThreshold) {
        this.code = code;
        this.name = name;
        this.crossCurrency = crossCurrency;
        this.settlementCurrency = settlementCurrency;
        this.roundingParty = roundingParty;
        this.dualApprovalThreshold = dualApprovalThreshold;
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

    public BigDecimal getDualApprovalThreshold() {
        return dualApprovalThreshold;
    }
}
