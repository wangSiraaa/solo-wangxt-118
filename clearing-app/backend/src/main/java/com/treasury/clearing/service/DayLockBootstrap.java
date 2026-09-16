package com.treasury.clearing.service;

import com.treasury.clearing.domain.SettlementDayLock;
import com.treasury.clearing.repo.DayLockRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 结算日锁行自举：在独立事务中确保锁行存在。独立成 Bean 以保证
 * {@code REQUIRES_NEW} 经由 Spring 代理生效（避免同类自调用事务不生效）。
 */
@Component
public class DayLockBootstrap {

    private final DayLockRepository repo;

    public DayLockBootstrap(DayLockRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureExists(LocalDate date) {
        if (repo.findById(date).isEmpty()) {
            try {
                repo.saveAndFlush(new SettlementDayLock(date));
            } catch (RuntimeException ignored) {
                // 并发唯一冲突：另一事务已创建该行，忽略。
            }
        }
    }
}
