package com.treasury.clearing.service;

import com.treasury.clearing.domain.AgreementParty;
import com.treasury.clearing.domain.BatchKind;
import com.treasury.clearing.domain.BatchStatus;
import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.ConflictException;
import com.treasury.clearing.domain.LegalEntity;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.domain.ReceivableStatus;
import com.treasury.clearing.domain.ReversalDecision;
import com.treasury.clearing.domain.ReversalRequest;
import com.treasury.clearing.domain.ReversalStatus;
import com.treasury.clearing.repo.AgreementPartyRepository;
import com.treasury.clearing.repo.ClearingBatchRepository;
import com.treasury.clearing.repo.ExcludedClaimRepository;
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
class ReversalServiceIntegrationTest {

    @Autowired private ReversalService reversalService;
    @Autowired private TrialService trialService;
    @Autowired private ClearingBatchRepository batchRepo;
    @Autowired private ReversalRequestRepository requestRepo;
    @Autowired private ReversalDecisionRepository decisionRepo;
    @Autowired private ReceivableRepository receivableRepo;
    @Autowired private ExcludedClaimRepository excludedRepo;
    @Autowired private LegalEntityRepository entityRepo;
    @Autowired private NettingAgreementRepository agreementRepo;
    @Autowired private AgreementPartyRepository partyRepo;
    @Autowired private BatchViewMapper viewMapper;
    @Autowired private com.treasury.clearing.repo.InvoiceCorrectionEventRepository eventRepo;
    @Autowired private com.treasury.clearing.repo.AdjustmentDecisionRepository adjDecRepo;
    @Autowired private com.treasury.clearing.repo.AdjustmentRequestRepository adjReqRepo;

    private LocalDate d;

    @BeforeEach
    void setUp() {
        excludedRepo.deleteAll();
        eventRepo.deleteAll();
        adjDecRepo.deleteAll();
        adjReqRepo.deleteAll();
        decisionRepo.deleteAll();
        requestRepo.deleteAll();
        batchRepo.deleteAll();
        receivableRepo.deleteAll();
        partyRepo.deleteAll();
        agreementRepo.deleteAll();
        entityRepo.deleteAll();

        Stream.of("A", "B", "C").forEach(c -> entityRepo.save(new LegalEntity(c, c)));
        d = LocalDate.of(2026, 8, 1);
    }

    private void agreement(String code, BigDecimal dualThreshold) {
        agreementRepo.save(new NettingAgreement(code, "协议", false, null, null, dualThreshold));
        Stream.of("A", "B", "C").forEach(c -> partyRepo.save(new AgreementParty(code, c)));
    }

    private void receivable(String id, String creditor, String debtor, String amount, String agreement) {
        receivableRepo.save(new Receivable(id, "I-" + id, creditor, debtor, "CNY", bd(amount),
                d, agreement, false, false, ReceivableStatus.ACTIVE));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** 大额环：清偿额百万级，门槛 10 万 → 双审。 */
    private ClearingBatch confirmDualThresholdBatch() {
        agreement("NA-DUAL", bd("100000"));
        receivable("c1", "A", "B", "1000000", "NA-DUAL");
        receivable("c2", "B", "C", "1000000", "NA-DUAL");
        receivable("c3", "C", "A", "1000000", "NA-DUAL");
        receivable("c4", "A", "B", "250000", "NA-DUAL");
        ClearingBatch sim = trialService.runTrial("dual", null, "专员", false);
        return trialService.confirmFromSimulation(sim.getId(), "主管");
    }

    /** 小额单票，门槛为空/很高 → 一审。 */
    private ClearingBatch confirmSingleApprovalBatch() {
        agreement("NA-SINGLE", null);
        receivable("s1", "A", "B", "5000", "NA-SINGLE");
        ClearingBatch sim = trialService.runTrial("single", null, "专员", false);
        return trialService.confirmFromSimulation(sim.getId(), "主管");
    }

    // ---- 双审正常路径 ----

    @Test
    void dualThreshold_requiresTwoDistinctApprovers_thenSingleReversal() {
        ClearingBatch cfm = confirmDualThresholdBatch();
        ReversalRequest req = reversalService.requestReversal(cfm.getId(), "依据变更", "专员");

        assertThat(req.getRequiredApprovals()).isEqualTo(2);
        assertThat(req.getApprovalsReceived()).isZero();
        assertThat(req.getThresholdAgreement()).isEqualTo("NA-DUAL");
        assertThat(req.getThresholdAmount()).isEqualByComparingTo("100000");
        assertThat(req.getGrossClearedAmount()).isEqualByComparingTo("3250000");

        // 一审通过：只到 PARTIALLY_APPROVED，不生成冲正、债权不动
        ReversalRequest afterFirst = reversalService.decide(cfm.getId(), "复核人甲", "同意一审",
                ReversalDecision.Outcome.APPROVE);
        assertThat(afterFirst.getStatus()).isEqualTo(ReversalStatus.PARTIALLY_APPROVED);
        assertThat(afterFirst.getApprovalsReceived()).isEqualTo(1);
        assertThat(afterFirst.getReversalBatchId()).isNull();
        assertThat(receivableRepo.findById("c1").orElseThrow().getStatus())
                .isEqualTo(ReceivableStatus.CLEARED);
        assertThat(decisionRepo.count()).isEqualTo(1);

        // 二审通过：PROCESSED + 唯一冲正 + 恢复债权
        ReversalRequest done = reversalService.decide(cfm.getId(), "复核人乙", "同意二审",
                ReversalDecision.Outcome.APPROVE);
        assertThat(done.getStatus()).isEqualTo(ReversalStatus.PROCESSED);
        assertThat(done.getApprovalsReceived()).isEqualTo(2);
        assertThat(done.getRestoredCount()).isEqualTo(4);
        assertThat(done.getReversalBatchId()).startsWith("RVL-");

        long reversalBatches = batchRepo.findAll().stream()
                .filter(b -> b.getKind() == BatchKind.REVERSAL).count();
        assertThat(reversalBatches).isEqualTo(1);
        assertThat(receivableRepo.findAll()).allSatisfy(r ->
                assertThat(r.getStatus()).isEqualTo(ReceivableStatus.ACTIVE));

        // 完整决议链：2 条 APPROVE，seq 递增，前后状态留痕，末条带冲正批次
        List<ReversalDecision> decisions = decisionRepo.listByRequest(req.getId());
        assertThat(decisions).hasSize(2);
        assertThat(decisions.get(0).getSeq()).isEqualTo(1);
        assertThat(decisions.get(0).getStatusBefore()).isEqualTo(ReversalStatus.REQUESTED);
        assertThat(decisions.get(0).getStatusAfter()).isEqualTo(ReversalStatus.PARTIALLY_APPROVED);
        assertThat(decisions.get(0).getReversalBatchId()).isNull();
        assertThat(decisions.get(1).getSeq()).isEqualTo(2);
        assertThat(decisions.get(1).getStatusBefore()).isEqualTo(ReversalStatus.PARTIALLY_APPROVED);
        assertThat(decisions.get(1).getStatusAfter()).isEqualTo(ReversalStatus.PROCESSED);
        assertThat(decisions.get(1).getReversalBatchId()).isEqualTo(done.getReversalBatchId());
    }

    // ---- 一审兼容 ----

    @Test
    void singleThreshold_compatibleWithOneApproval() {
        ClearingBatch cfm = confirmSingleApprovalBatch();
        ReversalRequest req = reversalService.requestReversal(cfm.getId(), "r", "专员");
        assertThat(req.getRequiredApprovals()).isEqualTo(1);

        ReversalRequest done = reversalService.decide(cfm.getId(), "复核人甲", "ok",
                ReversalDecision.Outcome.APPROVE);
        assertThat(done.getStatus()).isEqualTo(ReversalStatus.PROCESSED);
        assertThat(done.getApprovalsReceived()).isEqualTo(1);
        assertThat(decisionRepo.listByRequest(req.getId())).hasSize(1);
        assertThat(batchRepo.findAll().stream().filter(b -> b.getKind() == BatchKind.REVERSAL).count())
                .isEqualTo(1);
        assertThat(receivableRepo.findById("s1").orElseThrow().getStatus())
                .isEqualTo(ReceivableStatus.ACTIVE);
    }

    // ---- 冲突：自审 / 同人重复 / 乱序 / 驳回后再批 ----

    @Test
    void selfApproval_isRejected() {
        ClearingBatch cfm = confirmDualThresholdBatch();
        reversalService.requestReversal(cfm.getId(), "r", "专员");
        assertThatThrownBy(() -> reversalService.decide(cfm.getId(), "专员", "自审",
                ReversalDecision.Outcome.APPROVE))
                .isInstanceOf(ConflictException.class).hasMessageContaining("申请人不能审批");
        // 不多记决议、批次仍待审批
        assertThat(decisionRepo.count()).isZero();
        assertThat(batchRepo.findById(cfm.getId()).orElseThrow().getStatus())
                .isEqualTo(BatchStatus.REVERSAL_PENDING);
    }

    @Test
    void sameApproverTwice_doesNotConsumeSecondSeat() {
        ClearingBatch cfm = confirmDualThresholdBatch();
        reversalService.requestReversal(cfm.getId(), "r", "专员");
        reversalService.decide(cfm.getId(), "复核人甲", "first", ReversalDecision.Outcome.APPROVE);

        assertThatThrownBy(() -> reversalService.decide(cfm.getId(), "复核人甲", "again",
                ReversalDecision.Outcome.APPROVE))
                .isInstanceOf(ConflictException.class).hasMessageContaining("已对该撤销申请提交过决议");
        // 仍停在 PARTIALLY_APPROVED，决议只有 1 条，债权未恢复
        ReversalRequest req = requestRepo.findByOriginalBatchId(cfm.getId()).orElseThrow();
        assertThat(req.getStatus()).isEqualTo(ReversalStatus.PARTIALLY_APPROVED);
        assertThat(decisionRepo.count()).isEqualTo(1);
        assertThat(receivableRepo.findById("c1").orElseThrow().getStatus())
                .isEqualTo(ReceivableStatus.CLEARED);
    }

    @Test
    void rejectAfterFirstApproval_returnsToConfirmed_andNoFurtherApproval() {
        ClearingBatch cfm = confirmDualThresholdBatch();
        reversalService.requestReversal(cfm.getId(), "r", "专员");
        reversalService.decide(cfm.getId(), "复核人甲", "first", ReversalDecision.Outcome.APPROVE);

        ReversalRequest rejected = reversalService.decide(cfm.getId(), "复核人乙", "不通过",
                ReversalDecision.Outcome.REJECT);
        assertThat(rejected.getStatus()).isEqualTo(ReversalStatus.REJECTED);
        assertThat(batchRepo.findById(cfm.getId()).orElseThrow().getStatus())
                .isEqualTo(BatchStatus.CONFIRMED);
        // 决议链保留一审通过 + 一条驳回；债权全部仍 CLEARED
        assertThat(decisionRepo.count()).isEqualTo(2);
        assertThat(receivableRepo.findAll()).allSatisfy(r ->
                assertThat(r.getStatus()).isEqualTo(ReceivableStatus.CLEARED));

        // 驳回后再批准 → 409，不新增决议、不冲正
        assertThatThrownBy(() -> reversalService.decide(cfm.getId(), "复核人丙", "补批",
                ReversalDecision.Outcome.APPROVE))
                .isInstanceOf(ConflictException.class);
        assertThat(decisionRepo.count()).isEqualTo(2);
        assertThat(batchRepo.findAll().stream().noneMatch(b -> b.getKind() == BatchKind.REVERSAL))
                .isTrue();
    }

    @Test
    void afterProcessed_extraApprovalsConflict_andSingleReversal() {
        ClearingBatch cfm = confirmSingleApprovalBatch();
        reversalService.requestReversal(cfm.getId(), "r", "专员");
        reversalService.decide(cfm.getId(), "复核人甲", "ok", ReversalDecision.Outcome.APPROVE);

        assertThatThrownBy(() -> reversalService.decide(cfm.getId(), "复核人乙", "extra",
                ReversalDecision.Outcome.APPROVE))
                .isInstanceOf(ConflictException.class).hasMessageContaining("撤销已处理完成");
        assertThat(decisionRepo.count()).isEqualTo(1);
        assertThat(batchRepo.findAll().stream().filter(b -> b.getKind() == BatchKind.REVERSAL).count())
                .isEqualTo(1);
    }

    // ---- 并发：两人抢最后名额只成功一次 ----

    @Test
    void concurrentFinalSeat_onlyOneWins() throws InterruptedException {
        ClearingBatch cfm = confirmDualThresholdBatch();
        reversalService.requestReversal(cfm.getId(), "r", "专员");
        // 一审固定
        reversalService.decide(cfm.getId(), "复核人甲", "first", ReversalDecision.Outcome.APPROVE);

        int n = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<String> approvers = List.of("X1", "X2", "X3", "X4");
        List<Thread> threads = Stream.iterate(0, i -> i + 1).limit(n).map(i -> new Thread(() -> {
            try {
                start.await();
                reversalService.decide(cfm.getId(), approvers.get(i), "race",
                        ReversalDecision.Outcome.APPROVE);
                ok.incrementAndGet();
            } catch (ConflictException e) {
                conflict.incrementAndGet();
            } catch (Exception e) {
                conflict.incrementAndGet();
            } finally {
                done.countDown();
            }
        })).toList();
        threads.forEach(Thread::start);
        start.countDown();
        done.await();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(n - 1);

        ReversalRequest req = requestRepo.findByOriginalBatchId(cfm.getId()).orElseThrow();
        assertThat(req.getStatus()).isEqualTo(ReversalStatus.PROCESSED);
        assertThat(req.getApprovalsReceived()).isEqualTo(2);
        // 决议：一审 + 唯一末审 = 2；只生成一条冲正
        assertThat(decisionRepo.listByRequest(req.getId())).hasSize(2);
        assertThat(batchRepo.findAll().stream().filter(b -> b.getKind() == BatchKind.REVERSAL).count())
                .isEqualTo(1);
    }

    // ---- 恢复：首审已落库，后续失败/重启后续审不重复占名额 ----

    @Test
    void firstDecisionPersisted_secondApproverContinuesSafely() {
        ClearingBatch cfm = confirmDualThresholdBatch();
        ReversalRequest req = reversalService.requestReversal(cfm.getId(), "r", "专员");
        // 首审独立提交（模拟之后服务失败/重启：首审决议已持久化）
        reversalService.decide(cfm.getId(), "复核人甲", "first", ReversalDecision.Outcome.APPROVE);

        // 模拟重启后重放同一审批人 → 409，不重复占名额
        assertThatThrownBy(() -> reversalService.decide(cfm.getId(), "复核人甲", "retry",
                ReversalDecision.Outcome.APPROVE))
                .isInstanceOf(ConflictException.class);
        assertThat(decisionRepo.listByRequest(req.getId())).hasSize(1);

        // 第二审批人安全继续，恰好补齐并冲正一次
        ReversalRequest done = reversalService.decide(cfm.getId(), "复核人乙", "second",
                ReversalDecision.Outcome.APPROVE);
        assertThat(done.getStatus()).isEqualTo(ReversalStatus.PROCESSED);
        assertThat(decisionRepo.listByRequest(req.getId())).hasSize(2);
        assertThat(batchRepo.findAll().stream().filter(b -> b.getKind() == BatchKind.REVERSAL).count())
                .isEqualTo(1);
        assertThat(receivableRepo.findAll()).allSatisfy(r ->
                assertThat(r.getStatus()).isEqualTo(ReceivableStatus.ACTIVE));
    }

    // ---- 终态/非法目标 ----

    @Test
    void cannotReverseSimulated_orReversalBatch() {
        ClearingBatch sim = trialService.runTrial("sim", null, "t", false);
        assertThatThrownBy(() -> reversalService.requestReversal(sim.getId(), "r", "x"))
                .isInstanceOf(ConflictException.class);

        ClearingBatch cfm = confirmSingleApprovalBatch();
        reversalService.requestReversal(cfm.getId(), "r", "x");
        reversalService.decide(cfm.getId(), "复核人甲", "ok", ReversalDecision.Outcome.APPROVE);
        String reversalId = requestRepo.findByOriginalBatchId(cfm.getId())
                .orElseThrow().getReversalBatchId();
        assertThatThrownBy(() -> reversalService.requestReversal(reversalId, "r", "x"))
                .isInstanceOf(ConflictException.class);
    }
}
