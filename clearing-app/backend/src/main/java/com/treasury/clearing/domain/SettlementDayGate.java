package com.treasury.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 关账竞争门（每次关账尝试一行，按 generation 递增）。
 *
 * <p>用于在“关账 vs 确认生效”竞争窗口确定唯一胜者，且独立于 {@code settlement_day_lock}
 * 的行锁队列——关账在排队取日锁前先在独立事务原子登记 PENDING：
 * <ul>
 *   <li>确认持日锁期间发现 PENDING（关账已在排队）→ 置 SUPERSEDED 后提交，确认胜，关账取锁后 409；</li>
 *   <li>关账取锁后仍为 PENDING 且无有效报表 → 关账胜，提交快照并置 CLOSED，确认随后被报表状态 409。</li>
 * </ul>
 * {@code activeToken} 仅在 PENDING 时等于结算日，否则为 null，配合唯一约束保证
 * “同一结算日至多一个进行中的关账意图”，该写法在 PostgreSQL 与 H2 均可移植（多 NULL 不冲突）。
 */
@Entity
@Table(name = "settlement_day_gate")
public class SettlementDayGate {

    public enum State { PENDING, SUPERSEDED, CLOSED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "generation", nullable = false)
    private int generation;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

    /** PENDING 时等于结算日期字符串，其余状态为 null；唯一约束保证每日至多一个 PENDING。 */
    @Column(name = "active_token", unique = true, length = 10)
    private String activeToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SettlementDayGate() {
    }

    public SettlementDayGate(LocalDate settlementDate, int generation, Instant createdAt) {
        this.settlementDate = settlementDate;
        this.generation = generation;
        this.state = State.PENDING;
        this.activeToken = settlementDate.toString();
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public LocalDate getSettlementDate() { return settlementDate; }
    public int getGeneration() { return generation; }
    public State getState() { return state; }
    public String getActiveToken() { return activeToken; }
    public Instant getCreatedAt() { return createdAt; }

    public void supersede() {
        if (this.state != State.PENDING) {
            throw new ConflictException("只有 PENDING 关账门可被确认取代，当前: " + this.state);
        }
        this.state = State.SUPERSEDED;
        this.activeToken = null;
    }

    public void close() {
        if (this.state != State.PENDING) {
            throw new ConflictException("只有 PENDING 关账门可完成关账，当前: " + this.state);
        }
        this.state = State.CLOSED;
        this.activeToken = null;
    }

    public void cancel() {
        if (this.state != State.PENDING) {
            return;
        }
        this.state = State.CANCELLED;
        this.activeToken = null;
    }
}
