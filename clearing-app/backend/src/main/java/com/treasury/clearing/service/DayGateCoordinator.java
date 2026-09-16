package com.treasury.clearing.service;

import com.treasury.clearing.domain.ConflictException;
import com.treasury.clearing.domain.SettlementDayGate;
import com.treasury.clearing.repo.DayLockRepository;
import com.treasury.clearing.repo.SettlementDayGateRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 关账竞争门协调器：在“关账 vs 确认生效”竞争窗口确定唯一胜者。
 *
 * <p>两段式，全部状态读写都在结算日行锁（FOR UPDATE）保护下：
 * <ol>
 *   <li>{@link #registerCloseIntent}：独立 REQUIRES_NEW 事务，持有日行锁插入 PENDING 门后提交释放；</li>
 *   <li>关账主事务与确认事务随后竞争同一把日行锁，先拿到者决定门终态：
 *     <ul>
 *       <li>关账主事务先拿到：门仍 PENDING → 建快照并置 CLOSED；确认后拿到锁见 CLOSED 报表 → 409；</li>
 *       <li>确认先拿到：见 PENDING 门则置 SUPERSEDED 并提交；关账主事务拿到锁后 claim 见 SUPERSEDED → 409。</li>
 *     </ul>
 *   </li>
 * </ol>
 * 门状态转换与报表写入均在同一把日行锁临界区内串行，因此不可能两者都成功；
 * 失败的关账在同事务回滚（PENDING 门不落库），失败的确认整体回滚，无半成品。
 */
@Component
public class DayGateCoordinator {

    private final SettlementDayGateRepository gateRepo;
    private final DayLockRepository dayLockRepo;
    private final DayLockBootstrap bootstrap;

    public DayGateCoordinator(SettlementDayGateRepository gateRepo,
                              DayLockRepository dayLockRepo,
                              DayLockBootstrap bootstrap) {
        this.gateRepo = gateRepo;
        this.dayLockRepo = dayLockRepo;
        this.bootstrap = bootstrap;
    }

    /** 独立短事务：持有日行锁期间插入 PENDING 关账门。重复关账在 active_token 唯一约束上 409。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementDayGate registerCloseIntent(LocalDate date) {
        bootstrap.ensureExists(date);
        dayLockRepo.findLockedByDate(date)
                .orElseThrow(() -> new ConflictException("结算日锁不可用: " + date));
        int next = gateRepo.maxGeneration(date) + 1;
        try {
            return gateRepo.saveAndFlush(new SettlementDayGate(date, next, Instant.now()));
        } catch (RuntimeException dup) {
            throw new ConflictException("结算日 " + date + " 已有进行中的关账操作，不能重复关账");
        }
    }

    /** 确认持日行锁后调用：把排队中的 PENDING 关账门置 SUPERSEDED（确认胜出）。 */
    @Transactional
    public void supersedePendingForConfirm(LocalDate date) {
        gateRepo.findPendingByTokenForUpdate(date.toString())
                .ifPresent(SettlementDayGate::supersede);
    }

    /** 关账主事务持日行锁后确认竞争结果；门已被确认 SUPERSEDED → 409。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementDayGate claim(LocalDate date, Long gateId) {
        SettlementDayGate gate = gateRepo.findById(gateId)
                .orElseThrow(() -> new ConflictException("关账竞争门缺失: " + date));
        if (gate.getState() == SettlementDayGate.State.SUPERSEDED) {
            throw new ConflictException("结算日 " + date
                    + " 在关账排队期间已有确认生效，关账竞争失败（确认胜出），请重开关账以补入最新批次");
        }
        if (gate.getState() != SettlementDayGate.State.PENDING) {
            throw new ConflictException("关账门状态异常: " + gate.getState());
        }
        return gate;
    }

    @Transactional
    public void completeClose(SettlementDayGate gate) {
        gate.close();
        gateRepo.save(gate);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelPending(Long gateId) {
        Optional<SettlementDayGate> gate = gateRepo.findById(gateId);
        gate.ifPresent(SettlementDayGate::cancel);
    }
}
