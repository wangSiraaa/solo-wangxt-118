package com.treasury.clearing.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * 清算组：同一互抵协议 + 同一清算币种。
 * 不允许跨币种互抵的协议会按币种再拆成多个组。
 */
@Entity
@Table(name = "clearing_group")
public class ClearingGroup {

    @Id
    @Column(length = 48)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id")
    private ClearingBatch batch;

    @Column(name = "agreement_code", nullable = false, length = 32)
    private String agreementCode;

    /** 组的清算币种；同币种组即原始币种，跨币种组为协议结算币种。 */
    @Column(name = "clearing_currency", nullable = false, length = 3)
    private String clearingCurrency;

    @Column(name = "cross_currency", nullable = false)
    private boolean crossCurrency;

    /** 组内原始债权笔数（参与清算口径）。 */
    @Column(name = "claim_count", nullable = false)
    private int claimCount;

    /** 组内原始债权总金额（各原始币种金额，仅用于展示，跨币种组不做简单加总）。 */
    @Column(name = "gross_claims_display", length = 512)
    private String grossClaimsDisplay;

    /** 组内净头寸轧差后的非零收付笔数。 */
    @Column(name = "net_entry_count", nullable = false)
    private int netEntryCount;

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NetPosition> positions = new ArrayList<>();

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ClearingEntry> entries = new ArrayList<>();

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InvoiceDischarge> discharges = new ArrayList<>();

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RoundingLine> roundingLines = new ArrayList<>();

    protected ClearingGroup() {
    }

    public ClearingGroup(String id, ClearingBatch batch, String agreementCode,
                         String clearingCurrency, boolean crossCurrency, int claimCount,
                         String grossClaimsDisplay, int netEntryCount) {
        this.id = id;
        this.batch = batch;
        this.agreementCode = agreementCode;
        this.clearingCurrency = clearingCurrency;
        this.crossCurrency = crossCurrency;
        this.claimCount = claimCount;
        this.grossClaimsDisplay = grossClaimsDisplay;
        this.netEntryCount = netEntryCount;
    }

    public String getId() {
        return id;
    }

    public String getAgreementCode() {
        return agreementCode;
    }

    public String getClearingCurrency() {
        return clearingCurrency;
    }

    public boolean isCrossCurrency() {
        return crossCurrency;
    }

    public int getClaimCount() {
        return claimCount;
    }

    public String getGrossClaimsDisplay() {
        return grossClaimsDisplay;
    }

    public int getNetEntryCount() {
        return netEntryCount;
    }

    public List<NetPosition> getPositions() {
        return positions;
    }

    public List<ClearingEntry> getEntries() {
        return entries;
    }

    public List<InvoiceDischarge> getDischarges() {
        return discharges;
    }

    public List<RoundingLine> getRoundingLines() {
        return roundingLines;
    }

    public void addPosition(NetPosition p) {
        positions.add(p);
    }

    public void addEntry(ClearingEntry e) {
        entries.add(e);
    }

    public void addDischarge(InvoiceDischarge d) {
        discharges.add(d);
    }

    public void addRoundingLine(RoundingLine r) {
        roundingLines.add(r);
    }
}
