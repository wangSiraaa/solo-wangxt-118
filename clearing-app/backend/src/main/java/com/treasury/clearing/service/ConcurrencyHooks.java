package com.treasury.clearing.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 并发测试用可控闩锁。生产环境所有钩子为 no-op；测试可注册回调，
 * 在“登记关账意图 / 取日锁前后”等精确位置制造线程同步，强制指定交错顺序。
 */
@Component
public class ConcurrencyHooks {

    public volatile Runnable beforeConfirmAcquireLock = () -> {};
    public volatile Runnable afterConfirmAcquireLock = () -> {};
    public volatile Hook beforeCloseRegisterIntent = d -> {};
    public volatile Hook afterCloseRegisterIntent = d -> {};
    public volatile Runnable beforeCloseAcquireLock = () -> {};
    public volatile Runnable beforeDayLockEnsure = () -> {};

    public void reset() {
        beforeConfirmAcquireLock = () -> {};
        afterConfirmAcquireLock = () -> {};
        beforeCloseRegisterIntent = d -> {};
        afterCloseRegisterIntent = d -> {};
        beforeCloseAcquireLock = () -> {};
        beforeDayLockEnsure = () -> {};
    }

    public void confirmBeforeLock() { beforeConfirmAcquireLock.run(); }
    public void confirmAfterLock() { afterConfirmAcquireLock.run(); }
    public void closeBeforeIntent(LocalDate d) { beforeCloseRegisterIntent.accept(d); }
    public void closeAfterIntent(LocalDate d) { afterCloseRegisterIntent.accept(d); }
    public void closeBeforeLock() { beforeCloseAcquireLock.run(); }
    public void dayLockBeforeEnsure() { beforeDayLockEnsure.run(); }

    @FunctionalInterface
    public interface Hook {
        void accept(LocalDate date);
    }
}
