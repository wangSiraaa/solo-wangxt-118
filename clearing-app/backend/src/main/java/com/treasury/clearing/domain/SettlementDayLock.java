package com.treasury.clearing.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDate;

/**
 * 结算日统一互斥边界（每行一个结算日）。
 *
 * <p>关账、再开账、确认生效、撤销/差额更正末审在各自事务<b>开始时</b>对对应日期的此行加
 * 排他行锁（{@code SELECT ... FOR UPDATE}），从而：
 * <ul>
 *   <li>关账与确认对同一结算日严格串行，恰好一方成功；</li>
 *   <li>关账在持锁后重查，必能看到此前已提交的 CONFIRMED/ADJUSTMENT/REVERSAL 批次，快照完整；</li>
 *   <li>确认在持锁后复查该日是否已关账，已关账则 409 并整体回滚，无半成品批次或债权改动。</li>
 * </ul>
 * 锁行可在首个事务中惰性创建（独立 REQUIRES_NEW 事务），行本身一经创建长期复用。
 */
@Entity
@Table(name = "settlement_day_lock")
public class SettlementDayLock {

    @Id
    private LocalDate settlementDate;

    @Version
    private long version;

    protected SettlementDayLock() {
    }

    public SettlementDayLock(LocalDate settlementDate) {
        this.settlementDate = settlementDate;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }
}
