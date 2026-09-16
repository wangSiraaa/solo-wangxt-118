package com.treasury.clearing.service;

import com.treasury.clearing.domain.AgreementParty;
import com.treasury.clearing.domain.BatchStatus;
import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.ClosingReport;
import com.treasury.clearing.domain.ClosingStatus;
import com.treasury.clearing.domain.ConflictException;
import com.treasury.clearing.domain.LegalEntity;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.domain.ReceivableStatus;
import com.treasury.clearing.repo.AgreementPartyRepository;
import com.treasury.clearing.repo.ClearingBatchRepository;
import com.treasury.clearing.repo.ClosingReportRepository;
import com.treasury.clearing.repo.ExcludedClaimRepository;
import com.treasury.clearing.repo.LegalEntityRepository;
import com.treasury.clearing.repo.NettingAgreementRepository;
import com.treasury.clearing.repo.ReceivableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关账与确认生效的统一日互斥边界：两者在同一起跑线取 settlement_day_lock 行锁，
 * 恰好一方成功；关账成功则快照含确认批次，确认成功则关账 409 且无任何半成品。
 */
@SpringBootTest
class ClosingConfirmConcurrencyTest {

    @Autowired private TrialService trialService;
    @Autowired private ClosingService closingService;
    @Autowired private ClosingReportRepository reportRepo;
    @Autowired private ClearingBatchRepository batchRepo;
    @Autowired private ReceivableRepository receivableRepo;
    @Autowired private ExcludedClaimRepository excludedRepo;
    @Autowired private LegalEntityRepository entityRepo;
    @Autowired private NettingAgreementRepository agreementRepo;
    @Autowired private AgreementPartyRepository partyRepo;
    @Autowired private PlatformTransactionManager txManager;

    private LocalDate seedDate;

    @Autowired private IntegrationTestCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner.cleanAll();
        excludedRepo.deleteAll();
        reportRepo.deleteAll();
        batchRepo.deleteAll();
        receivableRepo.deleteAll();
        partyRepo.deleteAll();
        agreementRepo.deleteAll();
        entityRepo.deleteAll();

        Stream.of("A", "B", "C").forEach(c -> entityRepo.save(new LegalEntity(c, c)));
        agreementRepo.save(new NettingAgreement("NA", "p", false, null, null, null));
        Stream.of("A", "B", "C").forEach(c -> partyRepo.save(new AgreementParty("NA", c)));
        seedDate = LocalDate.of(2026, 8, 1);
        receivableRepo.save(new Receivable("cc1", "I1", "A", "B", "CNY",
                new BigDecimal("100"), seedDate, "NA", false, false, ReceivableStatus.ACTIVE));
    }

    private void seedSecondActiveClaim() {
        receivableRepo.save(new Receivable("cc2", "I2", "B", "A", "CNY",
                new BigDecimal("50"), seedDate, "NA", false, false, ReceivableStatus.ACTIVE));
    }

    /**
     * 两个线程在 CyclicBarrier 对齐后并发：
     * T1 关账某固定结算日；T2 在同一结算日确认一个新批次（confirmedAt 落在该日）。
     * 行锁保证恰好一方成功。
     */
    @Test
    void closeAndConfirmAreMutuallyExclusive_onlyOneSucceeds() throws Exception {
        // 确认生效时刻 = 当前（confirmedAt=now），关账日与之对齐为同一 UTC 日
        java.time.Instant nowish = java.time.Instant.now();
        LocalDate closeDate = ClosingService.dateOf(nowish);
        // 预置一个已确认批次（cc1），保证关账即使单独跑也非空
        ClearingBatch firstSim = inTx(() -> trialService.runTrial("first", null, "t", false));
        inTx(() -> trialService.confirmFromSimulation(firstSim.getId(), "t"));

        seedSecondActiveClaim(); // 第二个待确认发票，供并发确认使用

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger closeOk = new AtomicInteger();
        AtomicInteger closeConflict = new AtomicInteger();
        AtomicInteger confirmOk = new AtomicInteger();
        AtomicInteger confirmConflict = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();

        Thread closer = new Thread(() -> {
            try {
                barrier.await();
                inTx(() -> closingService.close(closeDate, "u"));
                closeOk.incrementAndGet();
            } catch (ConflictException e) {
                closeConflict.incrementAndGet();
            } catch (Exception e) {
                if (barrier.getNumberWaiting() == 0) { /* barrier broken by peer */ }
                otherErrors.incrementAndGet();
            }
        });

        Thread confirmer = new Thread(() -> {
            try {
                barrier.await();
                ClearingBatch sim = inTx(() ->
                        trialService.runTrial("concurrent", null, "t", false));
                inTx(() -> trialService.confirmFromSimulation(sim.getId(), "t"));
                confirmOk.incrementAndGet();
            } catch (ConflictException e) {
                confirmConflict.incrementAndGet();
            } catch (Exception e) {
                otherErrors.incrementAndGet();
            }
        });

        closer.start();
        confirmer.start();
        closer.join(30000);
        confirmer.join(30000);

        // 恰好一方成功
        assertThat(closeOk.get() + confirmOk.get()).isEqualTo(1);
        assertThat(otherErrors.get()).isZero();

        if (closeOk.get() == 1) {
            // 关账成功：确认必须失败且无半成品（cc2 仍 ACTIVE，无第二个 CONFIRMED 批次）
            assertThat(confirmConflict.get()).isEqualTo(1);
            assertSnapshotComplete(closeDate);
            assertThat(receivableRepo.findById("cc2").orElseThrow().getStatus())
                    .isEqualTo(ReceivableStatus.ACTIVE);
        } else {
            // 确认成功：关账必须 409，且没有任何关账快照
            assertThat(closeConflict.get()).isEqualTo(1);
            assertThat(reportRepo.findBySettlementDateOrderByReportVersionDesc(closeDate)).isEmpty();
            assertThat(receivableRepo.findById("cc2").orElseThrow().getStatus())
                    .isEqualTo(ReceivableStatus.CLEARED);
        }
    }

    private void assertSnapshotComplete(LocalDate date) {
        var reports = reportRepo.findBySettlementDateOrderByReportVersionDesc(date);
        assertThat(reports).hasSize(1);
        ClosingReport r = reports.get(0);
        assertThat(r.getStatus()).isEqualTo(ClosingStatus.CLOSED);
        // 快照必须包含当日全部已确认批次（首个 + 若确认先持锁则并发的也被关账重查到）
        long confirmedThatDay = batchRepo.findAll().stream()
                .filter(b -> b.getStatus() == BatchStatus.CONFIRMED)
                .count();
        assertThat((long) r.getBatchCount()).isEqualTo(confirmedThatDay);
    }

    private <T> T inTx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(txManager).execute(s -> action.get());
    }
}
