package com.treasury.clearing.service;

import com.treasury.clearing.domain.ConflictException;
import com.treasury.clearing.domain.SettlementDayLock;
import com.treasury.clearing.repo.DayLockRepository;
import jakarta.persistence.LockTimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 获取结算日统一互斥锁（在调用方事务内持有到提交）。
 * 关账/再开账/确认生效/撤销与更正末审等“生效型”写操作必须先 {@link #acquire}。
 */
@Component
public class DayLockService {

    private final DayLockRepository repo;
    private final DayLockBootstrap bootstrap;

    public DayLockService(DayLockRepository repo, DayLockBootstrap bootstrap) {
        this.repo = repo;
        this.bootstrap = bootstrap;
    }

    /**
     * 在当前事务内取该结算日排他锁并持有到提交。
     * 先在独立事务确保锁行存在（避免首次关账与首次确认互插），再 FOR UPDATE。
     * 锁等待超时统一转 409；调用方须已在事务中。
     */
    @Transactional
    public void acquire(LocalDate date) {
        bootstrap.ensureExists(date);
        try {
            SettlementDayLock lock = repo.findLockedByDate(date)
                    .orElseThrow(() -> new ConflictException("结算日锁不可用: " + date));
            if (lock.getSettlementDate() == null) {
                throw new ConflictException("结算日锁不可用: " + date);
            }
        } catch (LockTimeoutException e) {
            throw new ConflictException("结算日 " + date + " 正有关账/确认操作进行中，请重试");
        }
    }
}
