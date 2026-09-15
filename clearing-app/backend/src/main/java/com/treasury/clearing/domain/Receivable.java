package com.treasury.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;


import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 原始债权（可对应一张内部发票）。金额始终为正，方向由 creditor/debtor 表达。
 * 该表为不可变事实表：清算只会新增批次侧的清偿记录，不回写金额。
 */
@Entity
@Table(name = "receivable")
public class Receivable {

    @Id
    @Column(length = 40)
    private String id;

    /** 乐观锁：清偿/恢复并发时由 JPA 转成 409，防止重复改动状态。 */
    @Version
    private long version;

    /** 发票/合同号，资金人员从债务图可追溯到原始单据。 */
    @Column(name = "invoice_no", nullable = false, length = 40)
    private String invoiceNo;

    @Column(name = "creditor_code", nullable = false, length = 32)
    private String creditorCode;

    @Column(name = "debtor_code", nullable = false, length = 32)
    private String debtorCode;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal amount;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    /** 债权挂靠的互抵协议；为空表示没有任何互抵协议保护。 */
    @Column(name = "agreement_code", length = 32)
    private String agreementCode;

    /** 已质押给第三方的债权不得互抵（质押权人优先受偿）。 */
    @Column(name = "pledged", nullable = false)
    private boolean pledged;

    /** 存在争议的债权不得互抵。 */
    @Column(name = "disputed", nullable = false)
    private boolean disputed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReceivableStatus status;

    protected Receivable() {
    }

    public Receivable(String id, String invoiceNo, String creditorCode, String debtorCode,
                      String currency, BigDecimal amount, LocalDate invoiceDate,
                      String agreementCode, boolean pledged, boolean disputed,
                      ReceivableStatus status) {
        this.id = id;
        this.invoiceNo = invoiceNo;
        this.creditorCode = creditorCode;
        this.debtorCode = debtorCode;
        this.currency = currency;
        this.amount = amount;
        this.invoiceDate = invoiceDate;
        this.agreementCode = agreementCode;
        this.pledged = pledged;
        this.disputed = disputed;
        this.status = status;
    }

    public String getId() {
        return id;
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

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public String getAgreementCode() {
        return agreementCode;
    }

    public boolean isPledged() {
        return pledged;
    }

    public boolean isDisputed() {
        return disputed;
    }

    public ReceivableStatus getStatus() {
        return status;
    }

    public void markCleared() {
        this.status = ReceivableStatus.CLEARED;
    }

    /** 撤销冲正：把已确认清偿的债权恢复为活跃（仅允许从 CLEARED 恢复）。 */
    public void reactivate() {
        if (this.status != ReceivableStatus.CLEARED) {
            throw new ConflictException(
                    "债权 " + invoiceNo + " 当前状态为 " + status + "，非 CLEARED，不能恢复（可能已被并发操作改动）");
        }
        this.status = ReceivableStatus.ACTIVE;
    }
}
