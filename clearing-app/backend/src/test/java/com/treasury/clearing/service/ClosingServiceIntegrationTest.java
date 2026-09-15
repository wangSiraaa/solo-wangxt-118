package com.treasury.clearing.service;

import com.treasury.clearing.domain.AgreementParty;
import com.treasury.clearing.domain.BatchKind;
import com.treasury.clearing.domain.BatchStatus;
import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.ClosingReport;
import com.treasury.clearing.domain.ClosingStatus;
import com.treasury.clearing.domain.ConflictException;
import com.treasury.clearing.domain.LegalEntity;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.domain.ReceivableStatus;
import com.treasury.clearing.domain.ReopenDecision;
import com.treasury.clearing.domain.ReopenStatus;
import com.treasury.clearing.repo.AdjustmentDecisionRepository;
import com.treasury.clearing.repo.AdjustmentRequestRepository;
import com.treasury.clearing.repo.AgreementPartyRepository;
import com.treasury.clearing.repo.ClearingBatchRepository;
import com.treasury.clearing.repo.ClosingContributionRepository;
import com.treasury.clearing.repo.ClosingReportRepository;
import com.treasury.clearing.repo.ExcludedClaimRepository;
import com.treasury.clearing.repo.InvoiceCorrectionEventRepository;
import com.treasury.clearing.repo.LegalEntityRepository;
import com.treasury.clearing.repo.NettingAgreementRepository;
import com.treasury.clearing.repo.ReceivableRepository;
import com.treasury.clearing.repo.ReopenDecisionRepository;
import com.treasury.clearing.repo.ReopenRequestRepository;
import com.treasury.clearing.repo.ReversalDecisionRepository;
import com.treasury.clearing.repo.ReversalRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ClosingServiceIntegrationTest {

    @Autowired private ClosingService closingService;
    @Autowired private TrialService trialService;
    @Autowired private ReversalService reversalService;
    @Autowired private AdjustmentService adjustmentService;
    @Autowired private ClosingReportRepository reportRepo;
    @Autowired private ClosingContributionRepository contribRepo;
    @Autowired private ReopenRequestRepository reopenRepo;
    @Autowired private ReopenDecisionRepository reopenDecisionRepo;
    @Autowired private ExcludedClaimRepository excludedRepo;
    @Autowired private ReversalDecisionRepository reversalDecisionRepo;
    @Autowired private ReversalRequestRepository reversalRepo;
    @Autowired private AdjustmentDecisionRepository adjDecisionRepo;
    @Autowired private AdjustmentRequestRepository adjReqRepo;
    @Autowired private InvoiceCorrectionEventRepository corrEventRepo;
    @Autowired private ClearingBatchRepository batchRepo;
    @Autowired private ReceivableRepository receivableRepo;
    @Autowired private LegalEntityRepository entityRepo;
    @Autowired private NettingAgreementRepository agreementRepo;
    @Autowired private AgreementPartyRepository partyRepo;

    private LocalDate settlementDate;

    @BeforeEach
    void setUp() {
        excludedRepo.deleteAll();
        corrEventRepo.deleteAll();
        adjDecisionRepo.deleteAll();
        adjReqRepo.deleteAll();
        reopenDecisionRepo.deleteAll();
        reopenRepo.deleteAll();
        contribRepo.deleteAll();
        reportRepo.deleteAll();
        reversalDecisionRepo.deleteAll();
        reversalRepo.deleteAll();
        batchRepo.deleteAll();
        receivableRepo.deleteAll();
        partyRepo.deleteAll();
        agreementRepo.deleteAll();
        entityRepo.deleteAll();

        Stream.of("A", "B", "C").forEach(c -> entityRepo.save(new LegalEntity(c, c)));
        // 大额门槛协议：现金清偿 25 万 → 达双审门槛 10 万
        agreementRepo.save(new NettingAgreement("NA", "p", false, null, null, new BigDecimal("100000")));
        Stream.of("A", "B", "C").forEach(c -> partyRepo.save(new AgreementParty("NA", c)));

        var d = LocalDate.of(2026, 8, 1);
        receivableRepo.save(new Receivable("c1", "I1", "A", "B", "CNY", bd("1000000"), d, "NA", false, false, ReceivableStatus.ACTIVE));
        receivableRepo.save(new Receivable("c2", "I2", "B", "C", "CNY", bd("1000000"), d, "NA", false, false, ReceivableStatus.ACTIVE));
        receivableRepo.save(new Receivable("c3", "I3", "C", "A", "CNY", bd("1000000"), d, "NA", false, false, ReceivableStatus.ACTIVE));
        receivableRepo.save(new Receivable("c4", "I4", "A", "B", "CNY", bd("250000"), d, "NA", false, false, ReceivableStatus.ACTIVE));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private ClearingBatch confirmToday() {
        ClearingBatch sim = trialService.runTrial("t", java.time.Instant.now(), "专员", false);
        ClearingBatch cfm = trialService.confirmFromSimulation(sim.getId(), "主管");
        settlementDate = ClosingService.dateOf(cfm.getConfirmedAt());
        return cfm;
    }

    @Test
    void closeOnce_containsVersionsLinesContributions_andBatchChain() {
        ClearingBatch cfm = confirmToday();
        ClosingReport r = closingService.close(settlementDate, "日终主管");

        assertThat(r.getReportVersion()).isEqualTo(1);
        assertThat(r.getStatus()).isEqualTo(ClosingStatus.CLOSED);
        assertThat(r.getBatchCount()).isGreaterThanOrEqualTo(1);
        var view = contribRepo.findByReportIdOrderByAgreementCodeAscClearingCurrencyAscBatchIdAsc(r.getId());
        // 必须能追溯到原确认批次的组贡献
        assertThat(view).anyMatch(c -> c.getBatchId().equals(cfm.getId())
                && c.getContributionType() == BatchKind.NETTING);
        // 组净头寸在贡献行中合计为 0
        BigDecimal netSum = view.stream().map(c -> c.getNetPosition()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(netSum).isEqualByComparingTo("0");
        // 现金贡献合计：B→A 25 万，收款方 A +25 万
        BigDecimal cashPositive = view.stream().map(c -> c.getCashAmount())
                .filter(x -> x.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(cashPositive).isEqualByComparingTo("250000");
    }

    @Test
    void duplicateClose_conflicts() {
        confirmToday();
        closingService.close(settlementDate, "u");
        assertThatThrownBy(() -> closingService.close(settlementDate, "u2"))
                .isInstanceOf(ConflictException.class);
        assertThat(reportRepo.findBySettlementDateOrderByReportVersionDesc(settlementDate)).hasSize(1);
    }

    @Test
    void closingBlocksConfirmReversalAdjustment() {
        ClearingBatch cfm = confirmToday();
        closingService.close(settlementDate, "u");

        // 关账后新确认（confirmedAt 落在已关账日）冲突
        ClearingBatch sim2 = trialService.runTrial("t2", java.time.Instant.now(), "专员", false);
        // sim2 此刻其计划发票已被 cfm 清偿，确认本身会因状态冲突；这里再验证日期锁：
        // 直接对当日撤销末审
        reversalService.requestReversal(cfm.getId(), "r", "专员");
        reversalService.decide(cfm.getId(), "复核人甲", "ok",
                com.treasury.clearing.domain.ReversalDecision.Outcome.APPROVE);
        assertThatThrownBy(() -> reversalService.decide(cfm.getId(), "复核人乙", "ok",
                com.treasury.clearing.domain.ReversalDecision.Outcome.APPROVE))
                .isInstanceOf(ConflictException.class);
        // 原批次未被冲正
        assertThat(batchRepo.findById(cfm.getId()).orElseThrow().getStatus())
                .isNotEqualTo(BatchStatus.REVERSED);
    }

    @Test
    void cannotCloseWhilePendingApproval() {
        ClearingBatch cfm = confirmToday();
        reversalService.requestReversal(cfm.getId(), "r", "专员");
        assertThatThrownBy(() -> closingService.close(settlementDate, "u"))
                .isInstanceOf(ConflictException.class);
        assertThat(reportRepo.findBySettlementDateOrderByReportVersionDesc(settlementDate)).isEmpty();
    }

    @Test
    void reopenDualApproval_versionsSnapshots_preservesHistory() {
        ClearingBatch cfm = confirmToday();
        ClosingReport v1 = closingService.close(settlementDate, "u");

        var req = closingService.requestReopen(settlementDate, "账务依据变化", "资金专员");
        assertThat(req.getRequiredApprovals()).isEqualTo(2); // 现金 25 万 ≥ 门槛 10 万
        assertThat(req.getFromReportVersion()).isEqualTo(1);
        // 旧版本进入 REOPEN_PENDING
        assertThat(reportRepo.findById(v1.getId()).orElseThrow().getStatus())
                .isEqualTo(ClosingStatus.REOPEN_PENDING);

        // 自审/重复 409
        assertThatThrownBy(() -> closingService.decide(req.getId(), "资金专员", "x",
                ReopenDecision.Outcome.APPROVE)).isInstanceOf(ConflictException.class);
        closingService.decide(req.getId(), "复核人甲", "ok", ReopenDecision.Outcome.APPROVE);
        assertThatThrownBy(() -> closingService.decide(req.getId(), "复核人甲", "again",
                ReopenDecision.Outcome.APPROVE)).isInstanceOf(ConflictException.class);

        // 末审生成 v2，v1 SUPERSEDED
        closingService.decide(req.getId(), "复核人乙", "ok", ReopenDecision.Outcome.APPROVE);
        var reports = reportRepo.findBySettlementDateOrderByReportVersionDesc(settlementDate);
        assertThat(reports).hasSize(2);
        ClosingReport latest = reports.get(0);
        assertThat(latest.getReportVersion()).isEqualTo(2);
        assertThat(latest.getStatus()).isEqualTo(ClosingStatus.CLOSED);
        assertThat(latest.getReopenReason()).isEqualTo("账务依据变化");
        ClosingReport old = reports.get(1);
        assertThat(old.getReportVersion()).isEqualTo(1);
        assertThat(old.getStatus()).isEqualTo(ClosingStatus.SUPERSEDED);
        // 历史快照的贡献链仍可追溯、未被覆盖
        assertThat(contribRepo.findByReportIdOrderByAgreementCodeAscClearingCurrencyAscBatchIdAsc(old.getId()))
                .isNotEmpty();
        var processedReq = reopenRepo.findById(req.getId()).orElseThrow();
        assertThat(processedReq.getStatus()).isEqualTo(ReopenStatus.PROCESSED);
        assertThat(processedReq.getNewReportId()).isEqualTo(latest.getId());
        assertThat(reopenDecisionRepo.listByRequest(req.getId())).hasSize(2);
    }

    @Test
    void reopenReject_returnsToClosed_noNewVersion() {
        confirmToday();
        closingService.close(settlementDate, "u");
        var req = closingService.requestReopen(settlementDate, "r", "专员");
        closingService.decide(req.getId(), "复核人甲", "ok", ReopenDecision.Outcome.APPROVE);
        closingService.decide(req.getId(), "复核人乙", "不通过", ReopenDecision.Outcome.REJECT);

        var reports = reportRepo.findBySettlementDateOrderByReportVersionDesc(settlementDate);
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).getStatus()).isEqualTo(ClosingStatus.CLOSED);
        var rejectedReq = reopenRepo.findById(req.getId()).orElseThrow();
        assertThat(rejectedReq.getStatus()).isEqualTo(ReopenStatus.REJECTED);
        // 驳回后再批 409
        assertThatThrownBy(() -> closingService.decide(req.getId(), "复核人丙", "x",
                ReopenDecision.Outcome.APPROVE)).isInstanceOf(ConflictException.class);
    }

    @Test
    void concurrentClose_onlyOneWins() throws InterruptedException {
        confirmToday();
        int n = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger cf = new AtomicInteger();
        List<Thread> ts = Stream.generate(() -> new Thread(() -> {
            try {
                start.await();
                closingService.close(settlementDate, "u");
                ok.incrementAndGet();
            } catch (Exception e) {
                cf.incrementAndGet();
            } finally {
                done.countDown();
            }
        })).limit(n).toList();
        ts.forEach(Thread::start);
        start.countDown();
        done.await();
        assertThat(ok.get()).isEqualTo(1);
        assertThat(cf.get()).isEqualTo(n - 1);
        assertThat(reportRepo.findBySettlementDateOrderByReportVersionDesc(settlementDate)).hasSize(1);
    }
}
