package com.treasury.clearing.service;

import com.treasury.clearing.domain.AgreementParty;
import com.treasury.clearing.domain.BatchStatus;
import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.ClosingReport;
import com.treasury.clearing.domain.ConflictException;
import com.treasury.clearing.domain.LegalEntity;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.domain.ReceivableStatus;
import com.treasury.clearing.domain.SettlementDayGate;
import com.treasury.clearing.repo.AgreementPartyRepository;
import com.treasury.clearing.repo.ClearingBatchRepository;
import com.treasury.clearing.repo.ClosingReportRepository;
import com.treasury.clearing.repo.DayLockRepository;
import com.treasury.clearing.repo.ExcludedClaimRepository;
import com.treasury.clearing.repo.LegalEntityRepository;
import com.treasury.clearing.repo.NettingAgreementRepository;
import com.treasury.clearing.repo.ReceivableRepository;
import com.treasury.clearing.repo.SettlementDayGateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关账 vs 确认：两段式关账门 + 日行锁确保竞争窗口恰好一方胜。
 * 闩点：close 在“意图短事务提交后(afterIntent)”与“主事务取日锁前(beforeCloseLock)”；
 * confirm 在“取到日锁后(afterConfirmLock)”。
 */
@SpringBootTest
class ClosingConfirmConcurrencyTest {

    @Autowired private TrialService trialService;
    @Autowired private ClosingService closingService;
    @Autowired private ConcurrencyHooks hooks;
    @Autowired private IntegrationTestCleaner cleaner;
    @Autowired private ClosingReportRepository reportRepo;
    @Autowired private SettlementDayGateRepository gateRepo;
    @Autowired private DayLockRepository dayLockRepo;
    @Autowired private ClearingBatchRepository batchRepo;
    @Autowired private ReceivableRepository receivableRepo;
    @Autowired private ExcludedClaimRepository excludedRepo;
    @Autowired private LegalEntityRepository entityRepo;
    @Autowired private NettingAgreementRepository agreementRepo;
    @Autowired private AgreementPartyRepository partyRepo;
    @Autowired private PlatformTransactionManager txManager;

    private LocalDate date;

    @BeforeEach
    void setUp() {
        cleaner.cleanAll();
        hooks.reset();
        excludedRepo.deleteAll();
        reportRepo.deleteAll();
        batchRepo.deleteAll();
        receivableRepo.deleteAll();
        partyRepo.deleteAll();
        agreementRepo.deleteAll();
        entityRepo.deleteAll();

        entityRepo.save(new LegalEntity("A", "A"));
        entityRepo.save(new LegalEntity("B", "B"));
        agreementRepo.save(new NettingAgreement("NA", "p", false, null, null, null));
        partyRepo.save(new AgreementParty("NA", "A"));
        partyRepo.save(new AgreementParty("NA", "B"));
        date = ClosingService.dateOf(java.time.Instant.now());
    }

    @AfterEach
    void tearDown() {
        hooks.reset();
    }

    private void seedConfirmed(String id, String amount, LocalDate d) {
        receivableRepo.save(new Receivable(id, "I-" + id, "A", "B", "CNY",
                new BigDecimal(amount), d, "NA", false, false, ReceivableStatus.ACTIVE));
        ClearingBatch sim = inTx(() -> trialService.runTrial("b-" + id, null, "t", false));
        inTx(() -> trialService.confirmFromSimulation(sim.getId(), "t"));
    }

    private String prepareSim(String id, String amount, LocalDate d) {
        receivableRepo.save(new Receivable(id, "I-" + id, "A", "B", "CNY",
                new BigDecimal(amount), d, "NA", false, false, ReceivableStatus.ACTIVE));
        return inTx(() -> trialService.runTrial("s-" + id, null, "t", false)).getId();
    }

    /** 关账先胜：关账完成快照提交后，确认才取到日锁，确认 409 无半成品。 */
    @Test
    void closeWins_confirmConflicts_noHalfBakedConfirm() throws Exception {
        seedConfirmed("c1", "100", date);
        String simId = prepareSim("c2", "50", date);

        CountDownLatch intentCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> closeErr = new AtomicReference<>();
        AtomicReference<Throwable> confirmErr = new AtomicReference<>();

        hooks.afterCloseRegisterIntent = d -> intentCommitted.countDown();

        Thread closer = thread(() -> {
            try { inTx(() -> closingService.close(date, "u")); }
            catch (Throwable t) { closeErr.set(root(t)); }
        });
        Thread confirmer = thread(() -> {
            try {
                await(intentCommitted);                 // 等关账 PENDING 已提交
                // 此刻关账主事务大概率仍在进行；直接确认会等日锁，关账 CLOSED 提交后拿到锁→409
                inTx(() -> trialService.confirmFromSimulation(simId, "t"));
            } catch (Throwable t) {
                confirmErr.set(root(t));
            }
        });

        closer.start();
        confirmer.start();
        closer.join(20000);
        confirmer.join(20000);

        assertThat(closeErr.get()).isNull();
        assertThat(confirmErr.get()).isInstanceOf(ConflictException.class);

        List<ClosingReport> reports = reportRepo.findBySettlementDateOrderByReportVersionDesc(date);
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).getStatus().name()).isEqualTo("CLOSED");
        assertThat(reports.get(0).getBatchCount()).isEqualTo(1);
        assertThat(countConfirmed()).isEqualTo(1);
        assertThat(receivableRepo.findById("c2").orElseThrow().getStatus())
                .isEqualTo(ReceivableStatus.ACTIVE);
        assertThat(gateStates(date)).containsExactly(SettlementDayGate.State.CLOSED);
    }

    /**
     * 确认先胜：关账 PENDING 已登记但在主事务取日锁处被闩停；确认先取日锁把 PENDING 置 SUPERSEDED；
     * 放行关账后其 claim 409、无快照。
     */
    @Test
    void confirmWins_closeConflicts_noSnapshot() throws Exception {
        seedConfirmed("c1", "100", date);
        String simId = prepareSim("c2", "50", date);

        CountDownLatch intentCommitted = new CountDownLatch(1);
        CountDownLatch allowCloseMain = new CountDownLatch(1);
        CountDownLatch confirmDone = new CountDownLatch(1);
        AtomicReference<Throwable> closeErr = new AtomicReference<>();
        AtomicReference<Throwable> confirmErr = new AtomicReference<>();

        hooks.afterCloseRegisterIntent = d -> intentCommitted.countDown();
        hooks.beforeCloseAcquireLock = () -> await(allowCloseMain);

        Thread closer = thread(() -> {
            try { inTx(() -> closingService.close(date, "u")); }
            catch (Throwable t) { closeErr.set(root(t)); }
        });
        Thread confirmer = thread(() -> {
            try {
                await(intentCommitted);
                inTx(() -> trialService.confirmFromSimulation(simId, "t"));
            } catch (Throwable t) {
                confirmErr.set(root(t));
            } finally {
                confirmDone.countDown();
            }
        });

        closer.start();
        await(intentCommitted);
        confirmer.start();
        await(confirmDone);          // 确认已取日锁→SUPERSEDED→提交
        allowCloseMain.countDown();  // 放行关账主事务取锁
        closer.join(20000);
        confirmer.join(20000);

        assertThat(confirmErr.get()).isNull();
        assertThat(closeErr.get()).isInstanceOf(ConflictException.class);

        assertThat(reportRepo.findBySettlementDateOrderByReportVersionDesc(date)).isEmpty();
        assertThat(countConfirmed()).isEqualTo(2);
        assertThat(receivableRepo.findById("c2").orElseThrow().getStatus())
                .isEqualTo(ReceivableStatus.CLEARED);
        assertThat(gateStates(date)).containsExactly(SettlementDayGate.State.SUPERSEDED);

        // 失败关账无半成品：PENDING 已随失败回滚撤销为 CANCELLED 或保持 SUPERSEDED；
        // 顺序重开关账可成功补快照（含 2 批次）。
        ClosingReport retry = inTx(() -> closingService.close(date, "u2"));
        assertThat(retry.getStatus().name()).isEqualTo("CLOSED");
        assertThat(retry.getBatchCount()).isEqualTo(2);
    }

    /**
     * 首次锁行不存在：全新日期，关账意图短事务与确认都首次创建 settlement_day_lock。
     * 本用例不强制业务胜负（顺序由操作系统调度决定），只验证：并发首次建行的唯一冲突被
     * REQUIRES_NEW 独立事务吸收、无未恢复错误，最终锁行恰一行，且失败方后续可幂等恢复。
     */
    @Test
    void firstLockRowMissing_concurrentCreation_recoversToSingleLockRow() throws Exception {
        LocalDate fresh = date.plusDays(40);
        seedConfirmed("f1", "100", fresh);
        String simId = prepareSim("f2", "50", fresh);

        AtomicReference<Throwable> closeErr = new AtomicReference<>();
        AtomicReference<Throwable> confirmErr = new AtomicReference<>();

        Thread closer = thread(() -> {
            try { inTx(() -> closingService.close(fresh, "u")); }
            catch (Throwable t) { closeErr.set(root(t)); }
        });
        Thread confirmer = thread(() -> {
            try { inTx(() -> trialService.confirmFromSimulation(simId, "t")); }
            catch (Throwable t) { confirmErr.set(root(t)); }
        });

        closer.start();
        confirmer.start();
        closer.join(30000);
        confirmer.join(30000);

        // 无一出现“锁初始化失败/未恢复”类错误（冲突只可能是业务 ConflictException）
        for (Throwable e : new Throwable[]{closeErr.get(), confirmErr.get()}) {
            if (e != null) {
                assertThat(e).isInstanceOfAny(ConflictException.class);
            }
        }
        // 锁行恰好一行
        assertThat(inTx(() -> dayLockRepo.findById(fresh).isPresent() ? 1 : 0)).isEqualTo(1);

        // 无论谁先完成，后续关账都能成功并补出完整快照（顺序补快照/重试幂等）
        if (reportRepo.findBySettlementDateOrderByReportVersionDesc(fresh).stream()
                .noneMatch(r -> r.getStatus().name().equals("CLOSED"))) {
            ClosingReport report = inTx(() -> closingService.close(fresh, "u2"));
            assertThat(report).isNotNull();
        }
        // 最终态自洽：存在恰好一份有效 CLOSED 快照
        long closed = reportRepo.findBySettlementDateOrderByReportVersionDesc(fresh).stream()
                .filter(r -> r.getStatus().name().equals("CLOSED")).count();
        assertThat(closed).isEqualTo(1);
    }

    /** 失败关账重试幂等：审批中（已登记 PENDING）重复关账 409，无多余门。 */
    @Test
    void duplicateClose_whilePending_conflicts_singleGate() {
        seedConfirmed("g1", "100", date);
        CountDownLatch intentCommitted = new CountDownLatch(1);
        CountDownLatch allow = new CountDownLatch(1);
        hooks.afterCloseRegisterIntent = d -> intentCommitted.countDown();
        hooks.beforeCloseAcquireLock = () -> await(allow);

        AtomicReference<Throwable> firstErr = new AtomicReference<>();
        Thread closer = thread(() -> {
            try { inTx(() -> closingService.close(date, "u")); }
            catch (Throwable t) { firstErr.set(root(t)); }
        });
        closer.start();
        await(intentCommitted);

        // PENDING 已在，第二个关账请求在登记意图短事务即 409
        AtomicReference<Throwable> dupErr = new AtomicReference<>();
        try {
            inTx(() -> closingService.close(date, "u2"));
        } catch (Throwable t) {
            dupErr.set(root(t));
        }
        assertThat(dupErr.get()).isInstanceOf(ConflictException.class);
        assertThat(gateStates(date)).containsExactly(SettlementDayGate.State.PENDING);

        allow.countDown();
        try { closer.join(20000); } catch (InterruptedException ignored) { }
        assertThat(firstErr.get()).isNull();
        assertThat(gateStates(date)).containsExactly(SettlementDayGate.State.CLOSED);
    }

    private long countConfirmed() {
        return batchRepo.findAll().stream().filter(b -> b.getStatus() == BatchStatus.CONFIRMED).count();
    }

    private List<SettlementDayGate.State> gateStates(LocalDate d) {
        return gateRepo.listByDate(d).stream().map(SettlementDayGate::getState).toList();
    }

    private <T> T inTx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(txManager).execute(s -> action.get());
    }

    private Thread thread(Runnable r) {
        return new Thread(r);
    }

    private static Throwable root(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur instanceof ConflictException ? cur : t;
    }

    private static void await(CountDownLatch l) {
        try {
            if (!l.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
