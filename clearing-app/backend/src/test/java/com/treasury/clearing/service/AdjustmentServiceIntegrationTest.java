package com.treasury.clearing.service;

import com.treasury.clearing.domain.AdjustmentDecision;
import com.treasury.clearing.domain.AdjustmentRequest;
import com.treasury.clearing.domain.AdjustmentStatus;
import com.treasury.clearing.domain.AgreementParty;
import com.treasury.clearing.domain.BatchKind;
import com.treasury.clearing.domain.BatchStatus;
import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.ConflictException;
import com.treasury.clearing.domain.LegalEntity;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.domain.ReceivableStatus;
import com.treasury.clearing.repo.AdjustmentDecisionRepository;
import com.treasury.clearing.repo.AdjustmentRequestRepository;
import com.treasury.clearing.repo.AgreementPartyRepository;
import com.treasury.clearing.repo.ClearingBatchRepository;
import com.treasury.clearing.repo.ExcludedClaimRepository;
import com.treasury.clearing.repo.InvoiceCorrectionEventRepository;
import com.treasury.clearing.repo.LegalEntityRepository;
import com.treasury.clearing.repo.NettingAgreementRepository;
import com.treasury.clearing.repo.ReceivableRepository;
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
class AdjustmentServiceIntegrationTest {

    @Autowired private AdjustmentService adjustmentService;
    @Autowired private TrialService trialService;
    @Autowired private ReversalService reversalService;
    @Autowired private ClearingBatchRepository batchRepo;
    @Autowired private AdjustmentRequestRepository adjReqRepo;
    @Autowired private AdjustmentDecisionRepository adjDecRepo;
    @Autowired private InvoiceCorrectionEventRepository eventRepo;
    @Autowired private ReversalRequestRepository reversalRepo;
    @Autowired private ReversalDecisionRepository reversalDecisionRepo;
    @Autowired private ReceivableRepository receivableRepo;
    @Autowired private ExcludedClaimRepository excludedRepo;
    @Autowired private LegalEntityRepository entityRepo;
    @Autowired private NettingAgreementRepository agreementRepo;
    @Autowired private AgreementPartyRepository partyRepo;
    @Autowired private BatchViewMapper mapper;

    private LocalDate d;

    @BeforeEach
    void setUp() {
        excludedRepo.deleteAll();
        eventRepo.deleteAll();
        adjDecRepo.deleteAll();
        adjReqRepo.deleteAll();
        reversalDecisionRepo.deleteAll();
        reversalRepo.deleteAll();
        batchRepo.deleteAll();
        receivableRepo.deleteAll();
        partyRepo.deleteAll();
        agreementRepo.deleteAll();
        entityRepo.deleteAll();

        Stream.of("A", "B", "C").forEach(c -> entityRepo.save(new LegalEntity(c, c)));
        d = LocalDate.of(2026, 8, 1);
    }

    private void agreement(String code, BigDecimal threshold) {
        agreementRepo.save(new NettingAgreement(code, "p", false, null, null, threshold));
        Stream.of("A", "B", "C").forEach(c -> partyRepo.save(new AgreementParty(code, c)));
    }

    private void rv(String id, String cr, String db, String amt, String ag) {
        receivableRepo.save(new Receivable(id, "I-" + id, cr, db, "CNY", bd(amt), d,
                ag, false, false, ReceivableStatus.ACTIVE));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private ClearingBatch confirmedRing() {
        agreement("NA", bd("100000")); // 双审门槛 10 万
        rv("c1", "A", "B", "1000000", "NA");
        rv("c2", "B", "C", "1000000", "NA");
        rv("c3", "C", "A", "1000000", "NA");
        ClearingBatch sim = trialService.runTrial("t", null, "专员", false);
        return trialService.confirmFromSimulation(sim.getId(), "主管");
    }

    // ---- 正常：增额，双审，唯一差额批次，原批次仍 CONFIRMED，债权不变 ----

    @Test
    void increaseAmount_dualApproval_createsSingleAdjustmentBatch_preservesOriginAndDebt() {
        ClearingBatch cfm = confirmedRing();

        AdjustmentRequest req = adjustmentService.request(cfm.getId(),
                List.of(new AdjustmentService.CorrectionSpec("c1", bd("1100000"), null,
                        "发票增额 10 万", "2026-09 起")),
                "金额更正", "专员");

        assertThat(req.getRequiredApprovals()).isEqualTo(2); // |Δ|=100000 达门槛
        assertThat(batchRepo.findById(cfm.getId()).orElseThrow().getStatus())
                .isEqualTo(BatchStatus.ADJUSTMENT_PENDING);
        assertThat(eventRepo.listByRequest(req.getId())).hasSize(1);

        // 首审：PARTIAL，无差额批次，债权不变
        AdjustmentRequest first = adjustmentService.decide(cfm.getId(), "复核人甲", "ok",
                AdjustmentDecision.Outcome.APPROVE);
        assertThat(first.getStatus()).isEqualTo(AdjustmentStatus.PARTIALLY_APPROVED);
        assertThat(adjReqRepo.findByOriginalBatchId(cfm.getId()).orElseThrow()
                .getAdjustmentBatchId()).isNull();
        assertThat(receivableRepo.findById("c1").orElseThrow().getAmount())
                .isEqualByComparingTo("1000000"); // 原始金额未改
        assertThat(receivableRepo.findById("c1").orElseThrow().getStatus())
                .isEqualTo(ReceivableStatus.CLEARED);

        // 末审：生成唯一差额批次
        AdjustmentRequest done = adjustmentService.decide(cfm.getId(), "复核人乙", "ok",
                AdjustmentDecision.Outcome.APPROVE);
        assertThat(done.getStatus()).isEqualTo(AdjustmentStatus.PROCESSED);
        String adjId = done.getAdjustmentBatchId();
        assertThat(adjId).startsWith("ADJ-");

        // c1: A(债权) +100000, B(债务) -100000 → 一条差额指令 B→A 100000（经视图在事务内装配）
        var adjView = mapper.toViewById(adjId);
        var entries = adjView.groups().get(0).entries();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).type()).isEqualTo("ADJUSTMENT");
        assertThat(entries.get(0).fromEntity()).isEqualTo("B");
        assertThat(entries.get(0).toEntity()).isEqualTo("A");
        assertThat(entries.get(0).amount()).isEqualByComparingTo("100000");
        assertThat(adjView.adjustsBatchId()).isEqualTo(cfm.getId());

        // 原批次保持 CONFIRMED 且双向关联，清偿明细原样（经视图在事务内读取）
        var originView = mapper.toViewById(cfm.getId());
        assertThat(originView.status()).isEqualTo("CONFIRMED");
        assertThat(originView.adjustmentBatchId()).isEqualTo(adjId);
        int discharges = originView.groups().stream()
                .mapToInt(g -> g.discharges().size()).sum();
        assertThat(discharges).isEqualTo(3);
        // 原始债权金额/状态均未变
        assertThat(receivableRepo.findById("c1").orElseThrow().getAmount())
                .isEqualByComparingTo("1000000");
        assertThat(receivableRepo.findById("c1").orElseThrow().getStatus())
                .isEqualTo(ReceivableStatus.CLEARED);
    }

    @Test
    void smallDelta_singleApproval_compatible() {
        agreement("NA", null); // 无门槛→一审
        rv("s1", "A", "B", "5000", "NA");
        ClearingBatch sim = trialService.runTrial("t", null, "专员", false);
        ClearingBatch cfm = trialService.confirmFromSimulation(sim.getId(), "主管");

        AdjustmentRequest req = adjustmentService.request(cfm.getId(),
                List.of(new AdjustmentService.CorrectionSpec("s1", bd("5500"), null, "x", "now")),
                "r", "专员");
        assertThat(req.getRequiredApprovals()).isEqualTo(1);
        AdjustmentRequest done = adjustmentService.decide(cfm.getId(), "复核人甲", "ok",
                AdjustmentDecision.Outcome.APPROVE);
        assertThat(done.getStatus()).isEqualTo(AdjustmentStatus.PROCESSED);
        assertThat(adjDecRepo.listByRequest(req.getId())).hasSize(1);
    }

    // ---- 冲突：自审、同人重复、同发票重复、重复更正、与撤销互斥 ----

    @Test
    void conflicts_selfReview_duplicateApprover_duplicateInvoice_andReversalMutualExclusion() {
        ClearingBatch cfm = confirmedRing();
        adjustmentService.request(cfm.getId(),
                List.of(new AdjustmentService.CorrectionSpec("c1", bd("1100000"), null, "r", "now")),
                "r", "专员");

        assertThatThrownBy(() -> adjustmentService.decide(cfm.getId(), "专员", "x",
                AdjustmentDecision.Outcome.APPROVE)).isInstanceOf(ConflictException.class);
        adjustmentService.decide(cfm.getId(), "复核人甲", "ok", AdjustmentDecision.Outcome.APPROVE);
        assertThatThrownBy(() -> adjustmentService.decide(cfm.getId(), "复核人甲", "again",
                AdjustmentDecision.Outcome.APPROVE)).isInstanceOf(ConflictException.class);

        // 更正进行中不能发起撤销
        assertThatThrownBy(() -> reversalService.requestReversal(cfm.getId(), "r", "x"))
                .isInstanceOf(ConflictException.class);

        // 完成更正
        adjustmentService.decide(cfm.getId(), "复核人乙", "ok", AdjustmentDecision.Outcome.APPROVE);

        // 已更正批次不能再更正，也不能撤销（单批次唯一更正/冲正）
        assertThatThrownBy(() -> adjustmentService.request(cfm.getId(),
                List.of(new AdjustmentService.CorrectionSpec("c2", bd("100"), null, "r", "now")),
                "r", "专员")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> reversalService.requestReversal(cfm.getId(), "r", "x"))
                .isInstanceOf(ConflictException.class);

        // 决议数仍为 2，债权未被改动
        AdjustmentRequest req = adjReqRepo.findByOriginalBatchId(cfm.getId()).orElseThrow();
        assertThat(adjDecRepo.listByRequest(req.getId())).hasSize(2);
        assertThat(receivableRepo.findById("c1").orElseThrow().getAmount())
                .isEqualByComparingTo("1000000");
    }

    @Test
    void cannotAdjustSimulated_orAdjustmentBatch() {
        ClearingBatch sim = trialService.runTrial("sim", null, "t", false);
        assertThatThrownBy(() -> adjustmentService.request(sim.getId(),
                List.of(new AdjustmentService.CorrectionSpec("x", bd("1"), null, "r", "now")),
                "r", "x")).isInstanceOf(ConflictException.class);

        ClearingBatch cfm = confirmedRing();
        adjustmentService.request(cfm.getId(),
                List.of(new AdjustmentService.CorrectionSpec("c1", bd("1100000"), null, "r", "now")),
                "r", "专员");
        adjustmentService.decide(cfm.getId(), "复核人甲", "ok", AdjustmentDecision.Outcome.APPROVE);
        adjustmentService.decide(cfm.getId(), "复核人乙", "ok", AdjustmentDecision.Outcome.APPROVE);
        String adjId = adjReqRepo.findByOriginalBatchId(cfm.getId())
                .orElseThrow().getAdjustmentBatchId();
        assertThatThrownBy(() -> adjustmentService.request(adjId,
                List.of(new AdjustmentService.CorrectionSpec("c1", bd("1"), null, "r", "now")),
                "r", "x")).isInstanceOf(ConflictException.class);
    }

    // ---- 并发抢末票 ----

    @Test
    void concurrentFinalSeat_onlyOneWins() throws InterruptedException {
        ClearingBatch cfm = confirmedRing();
        adjustmentService.request(cfm.getId(),
                List.of(new AdjustmentService.CorrectionSpec("c1", bd("1100000"), null, "r", "now")),
                "r", "专员");
        adjustmentService.decide(cfm.getId(), "复核人甲", "ok", AdjustmentDecision.Outcome.APPROVE);

        int n = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger cf = new AtomicInteger();
        List<String> who = List.of("Y1", "Y2", "Y3", "Y4");
        List<Thread> ts = Stream.iterate(0, i -> i + 1).limit(n).map(i -> new Thread(() -> {
            try {
                start.await();
                adjustmentService.decide(cfm.getId(), who.get(i), "race",
                        AdjustmentDecision.Outcome.APPROVE);
                ok.incrementAndGet();
            } catch (Exception e) {
                cf.incrementAndGet();
            } finally {
                done.countDown();
            }
        })).toList();
        ts.forEach(Thread::start);
        start.countDown();
        done.await();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(cf.get()).isEqualTo(n - 1);
        AdjustmentRequest req = adjReqRepo.findByOriginalBatchId(cfm.getId()).orElseThrow();
        assertThat(req.getApprovalsReceived()).isEqualTo(2);
        assertThat(adjDecRepo.listByRequest(req.getId())).hasSize(2);
        long adjBatches = batchRepo.findAll().stream()
                .filter(b -> b.getKind() == BatchKind.ADJUSTMENT
                        && cfm.getId().equals(b.getAdjustsBatchId())).count();
        assertThat(adjBatches).isEqualTo(1);
    }

    // ---- 驳回 ----

    @Test
    void rejectAfterFirstApproval_returnsToConfirmed_noAdjustment() {
        ClearingBatch cfm = confirmedRing();
        adjustmentService.request(cfm.getId(),
                List.of(new AdjustmentService.CorrectionSpec("c1", bd("1100000"), null, "r", "now")),
                "r", "专员");
        adjustmentService.decide(cfm.getId(), "复核人甲", "ok", AdjustmentDecision.Outcome.APPROVE);
        AdjustmentRequest rej = adjustmentService.decide(cfm.getId(), "复核人乙", "不通过",
                AdjustmentDecision.Outcome.REJECT);

        assertThat(rej.getStatus()).isEqualTo(AdjustmentStatus.REJECTED);
        assertThat(batchRepo.findById(cfm.getId()).orElseThrow().getStatus())
                .isEqualTo(BatchStatus.CONFIRMED);
        assertThat(batchRepo.findAll().stream().noneMatch(b -> b.getKind() == BatchKind.ADJUSTMENT))
                .isTrue();
        // 驳回后再批准 409，决议不增加
        assertThatThrownBy(() -> adjustmentService.decide(cfm.getId(), "复核人丙", "x",
                AdjustmentDecision.Outcome.APPROVE)).isInstanceOf(ConflictException.class);
        AdjustmentRequest req = adjReqRepo.findByOriginalBatchId(cfm.getId()).orElseThrow();
        assertThat(adjDecRepo.listByRequest(req.getId())).hasSize(2);
    }
}
