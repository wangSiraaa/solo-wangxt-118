package com.treasury.clearing.service;

import com.treasury.clearing.domain.SettlementDayLock;
import com.treasury.clearing.repo.DayLockRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 结算日锁行自举：保证锁行存在。
 *
 * <p>插入放在独立 {@code REQUIRES_NEW} 事务；并发首次竞争的唯一冲突在该独立事务内自行回滚，
 * 不污染调用方事务。
 */
@Component
public class DayLockBootstrap {

    private final DayLockRepository repo;
    private final ConcurrencyHooks hooks;

    public DayLockBootstrap(DayLockRepository repo, ConcurrencyHooks hooks) {
        this.repo = repo;
        this.hooks = hooks;
    }

    /** 确保行存在：存在则不动；不存在则在本独立事务插入，唯一冲突向上抛由调用方重试。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureExists(LocalDate date) {
        hooks.dayLockBeforeEnsure();
        if (repo.findById(date).isEmpty()) {
            try {
                repo.saveAndFlush(new SettlementDayLock(date));
            } catch (RuntimeException concurrent) {
                // 并发首次创建：本独立事务回滚，交由调用方在外层新事务中确认可见
                throw concurrent;
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean exists(LocalDate date) {
        return repo.findById(date).isPresent();
    }

    /** 仅当行不存在时插入，返回是否新插。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryInsert(LocalDate date) {
        hooks.dayLockBeforeEnsure();
        if (repo.findById(date).isPresent()) {
            return false;
        }
        repo.saveAndFlush(new SettlementDayLock(date));
        return true;
    }
}
