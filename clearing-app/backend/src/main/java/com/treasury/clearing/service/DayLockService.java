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
     * 确保锁行存在（首次并发竞争时显式重试），再在当前事务 FOR UPDATE。
     * 首次插入的唯一冲突发生在独立 REQUIRES_NEW 事务中、自行回滚，不污染调用方事务。
     */
    @Transactional
    public void acquire(LocalDate date) {
        try {
            bootstrap.tryInsert(date);
        } catch (RuntimeException concurrentInsert) {
            // 另一方已在并发首次创建：确认可见；若尚未提交可见则稍候重试
            for (int i = 0; i < 10 && !bootstrap.exists(date); i++) {
                sleep(20);
            }
            if (!bootstrap.exists(date)) {
                // 对方可能回滚未提交，再尝试一次插入
                try {
                    bootstrap.tryInsert(date);
                } catch (RuntimeException again) {
                    if (!bootstrap.exists(date)) {
                        throw new ConflictException("结算日锁初始化失败，请重试: " + date);
                    }
                }
            }
        }
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
