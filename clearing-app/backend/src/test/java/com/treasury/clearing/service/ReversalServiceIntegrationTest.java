package com.treasury.clearing.service;

import com.treasury.clearing.domain.AgreementParty;
import com.treasury.clearing.domain.BatchKind;
import com.treasury.clearing.domain.BatchStatus;
import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.LegalEntity;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.domain.ReceivableStatus;
import com.treasury.clearing.domain.ReversalRequest;
import com.treasury.clearing.domain.ReversalStatus;
import com.treasury.clearing.repo.AgreementPartyRepository;
import com.treasury.clearing.repo.ClearingBatchRepository;
import com.treasury.clearing.repo.ExcludedClaimRepository;
import com.treasury.clearing.repo.LegalEntityRepository;
import com.treasury.clearing.repo.NettingAgreementRepository;
import com.treasury.clearing.repo.ReceivableRepository;
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
    @Autowired private ReceivableRepository receivableRepo;
    @Autowired private ExcludedClaimRepository excludedRepo;
    @Autowired private LegalEntityRepository entityRepo;
    @Autowired private NettingAgreementRepository agreementRepo;
    @Autowired private AgreementPartyRepository partyRepo;
    @Autowired private BatchViewMapper viewMapper;

    private com.treasury.clearing.dto.BatchView batchView(ClearingBatch b) {
        return viewMapper.toViewById(b.getId());
    }

    @BeforeEach
    void setUp() {
        excludedRepo.deleteAll();
        requestRepo.deleteAll();
        batchRepo.deleteAll();
        receivableRepo.deleteAll();
        partyRepo.deleteAll();
        agreementRepo.deleteAll();
        entityRepo.deleteAll();

        Stream.of("A", "B", "C").forEach(c -> entityRepo.save(new LegalEntity(c, c)));
        agreementRepo.save(new NettingAgreement("NA", "协议", false, null, null));
        Stream.of("A", "B", "C").forEach(c -> partyRepo.save(new AgreementParty("NA", c)));

        LocalDate d = LocalDate.of(2026, 8, 1);
        receivableRepo.save(new Receivable("c1", "I1", "A", "B", "CNY", bd("1000000"), d, "NA", false, false, ReceivableStatus.ACTIVE));
        receivableRepo.save(new Receivable("c2", "I2", "B", "C", "CNY", bd("1000000"), d, "NA", false, false, ReceivableStatus.ACTIVE));
        receivableRepo.save(new Receivable("c3", "I3", "C", "A", "CNY", bd("1000000"), d, "NA", false, false, ReceivableStatus.ACTIVE));
        receivableRepo.save(new Receivable("c4", "I4", "A", "B", "CNY", bd("250000"), d, "NA", false, false, ReceivableStatus.ACTIVE));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private ClearingBatch confirmRing() {
        return trialService.confirmFromSimulation(
                trialService.runTrial("t", null, "tester", false).getId(), "主管");
    }

    @Test
    void normalFlow_createsReversalBatch_restoresReceivables_keepsFullAudit() {
        ClearingBatch cfm = confirmRing();

        // 只有 CONFIRMED 可申请
        assertThatThrownBy(() -> reversalService.requestReversal("not-exist", "r", "x"))
                .isInstanceOf(java.util.NoSuchElementException.class);

        ReversalRequest req = reversalService.requestReversal(cfm.getId(), "账务依据变更", "资金专员");
        assertThat(req.getStatus()).isEqualTo(ReversalStatus.REQUESTED);
        ClearingBatch pending = batchRepo.findById(cfm.getId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(BatchStatus.REVERSAL_PENDING);
        assertThat(pending.getReversalRequestedAt()).isNotNull();

        ClearingBatch rev = reversalService.approveReversal(cfm.getId(), "资金主管");

        // 冲正批次
        assertThat(rev.getKind()).isEqualTo(BatchKind.REVERSAL);
        assertThat(rev.getStatus()).isEqualTo(BatchStatus.CONFIRMED);
        assertThat(rev.getReversesBatchId()).isEqualTo(cfm.getId());
        assertThat(rev.getValuationTime()).isEqualTo(cfm.getValuationTime());

        // 原批次终态 + 双向关联
        ClearingBatch origin = batchRepo.findById(cfm.getId()).orElseThrow();
        assertThat(origin.getStatus()).isEqualTo(BatchStatus.REVERSED);
        assertThat(origin.getReversalBatchId()).isEqualTo(rev.getId());
        assertThat(origin.getReversedAt()).isNotNull();

        // 原批次的发票清偿明细仍完整可追溯（经接口视图，在事务内装配）
        var originView = batchView(batchRepo.findById(cfm.getId()).orElseThrow());
        var revView = batchView(batchRepo.findById(rev.getId()).orElseThrow());
        int originDischarges = originView.groups().stream()
                .mapToInt(g -> g.discharges().size()).sum();
        assertThat(originDischarges).isEqualTo(4);
        int revDischarges = revView.groups().stream()
                .mapToInt(g -> g.discharges().size()).sum();
        assertThat(revDischarges).isEqualTo(4);
        // 冲正净头寸为原净头寸取反
        assertThat(revView.groups().get(0).positions().stream()
                .filter(p -> p.entityCode().equals("A")).findFirst().orElseThrow().netAmount())
                .isEqualByComparingTo("-250000");
        // 冲正指令方向交换
        var originEntry = originView.groups().get(0).entries().get(0);
        var revEntry = revView.groups().get(0).entries().get(0);
        assertThat(revEntry.fromEntity()).isEqualTo(originEntry.toEntity());
        assertThat(revEntry.toEntity()).isEqualTo(originEntry.fromEntity());

        // 审计
        ReversalRequest done = requestRepo.findByOriginalBatchId(cfm.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(ReversalStatus.PROCESSED);
        assertThat(done.getReversalBatchId()).isEqualTo(rev.getId());
        assertThat(done.getRestoredCount()).isEqualTo(4);
        assertThat(done.getRequestedBy()).isEqualTo("资金专员");
        assertThat(done.getApprovedBy()).isEqualTo("资金主管");
        assertThat(done.getProcessedAt()).isNotNull();
        assertThat(done.getReason()).isEqualTo("账务依据变更");

        // 本次确认实际清偿的 4 张全部恢复 ACTIVE
        assertThat(receivableRepo.findAll()).allSatisfy(r ->
                assertThat(r.getStatus()).isEqualTo(ReceivableStatus.ACTIVE));
    }

    @Test
    void duplicateRequest_andDuplicateApprove_onlySucceedOnce() {
        ClearingBatch cfm = confirmRing();
        reversalService.requestReversal(cfm.getId(), "r", "x");

        // 重复申请
        assertThatThrownBy(() -> reversalService.requestReversal(cfm.getId(), "r2", "x"))
                .isInstanceOf(com.treasury.clearing.domain.ConflictException.class);
        assertThat(requestRepo.findAll()).hasSize(1);

        reversalService.approveReversal(cfm.getId(), "主管");

        // 重复审批：409 且不新增冲正批次
        assertThatThrownBy(() -> reversalService.approveReversal(cfm.getId(), "主管"))
                .isInstanceOf(com.treasury.clearing.domain.ConflictException.class)
                .hasMessageContaining("RVL-");
        long reversalBatches = batchRepo.findAll().stream()
                .filter(b -> b.getKind() == BatchKind.REVERSAL
                        && b.getReversesBatchId().equals(cfm.getId())).count();
        assertThat(reversalBatches).isEqualTo(1);
        // 债权只恢复一次（仍 ACTIVE，且只有 4 张）
        assertThat(receivableRepo.findAll())
                .hasSize(4)
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReceivableStatus.ACTIVE));
    }

    @Test
    void cannotReverseSimulated_orReversalBatch() {
        ClearingBatch sim = trialService.runTrial("sim", null, "t", false);
        assertThatThrownBy(() -> reversalService.requestReversal(sim.getId(), "r", "x"))
                .isInstanceOf(com.treasury.clearing.domain.ConflictException.class);

        ClearingBatch cfm = confirmRing();
        reversalService.requestReversal(cfm.getId(), "r", "x");
        ClearingBatch rev = reversalService.approveReversal(cfm.getId(), "主管");
        // 冲正批次不可再撤销
        assertThatThrownBy(() -> reversalService.requestReversal(rev.getId(), "r", "x"))
                .isInstanceOf(com.treasury.clearing.domain.ConflictException.class);
    }

    @Test
    void failedProcessing_rollsBack_andSafeRetryProducesSingleReversal() {
        ClearingBatch cfm = confirmRing();
        reversalService.requestReversal(cfm.getId(), "r", "x");

        // 模拟半成品/并发：c1 已被外部恢复成 ACTIVE。审批恢复阶段要求 CLEARED→ACTIVE，
        // 遇到 ACTIVE 会抛 409，整个审批事务回滚。
        Receivable c1 = receivableRepo.findById("c1").orElseThrow();
        assertThat(c1.getStatus()).isEqualTo(ReceivableStatus.CLEARED);
        c1.reactivate();
        receivableRepo.save(c1);

        assertThatThrownBy(() -> reversalService.approveReversal(cfm.getId(), "主管"))
                .isInstanceOf(com.treasury.clearing.domain.ConflictException.class);

        // 失败后：原批次仍在待审批、申请仍 REQUESTED、没有任何冲正批次落库
        assertThat(batchRepo.findById(cfm.getId()).orElseThrow().getStatus())
                .isEqualTo(BatchStatus.REVERSAL_PENDING);
        assertThat(requestRepo.findByOriginalBatchId(cfm.getId()).orElseThrow().getStatus())
                .isEqualTo(ReversalStatus.REQUESTED);
        assertThat(batchRepo.findAll().stream().noneMatch(b -> b.getKind() == BatchKind.REVERSAL))
                .isTrue();

        // 修复后安全重试：恰好生成一条冲正，债权恢复一次
        Receivable fixed = receivableRepo.findById("c1").orElseThrow();
        fixed.markCleared();
        receivableRepo.save(fixed);
        ClearingBatch rev = reversalService.approveReversal(cfm.getId(), "主管");
        assertThat(rev.getReversesBatchId()).isEqualTo(cfm.getId());
        assertThat(batchRepo.findAll().stream()
                .filter(b -> b.getKind() == BatchKind.REVERSAL).count()).isEqualTo(1);
        assertThat(receivableRepo.findAll())
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReceivableStatus.ACTIVE));
    }

    @Test
    void concurrentRequests_onlyOneWins() throws InterruptedException {
        ClearingBatch cfm = confirmRing();
        int n = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Thread> threads = Stream.generate(() -> new Thread(() -> {
            try {
                start.await();
                reversalService.requestReversal(cfm.getId(), "r", "x");
                ok.incrementAndGet();
            } catch (com.treasury.clearing.domain.ConflictException e) {
                conflict.incrementAndGet();
            } catch (Exception e) {
                // 乐观锁异常同样计为冲突
                conflict.incrementAndGet();
            } finally {
                done.countDown();
            }
        })).limit(n).toList();
        threads.forEach(Thread::start);
        start.countDown();
        done.await();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(n - 1);
        assertThat(requestRepo.findAll()).hasSize(1);
        assertThat(batchRepo.findById(cfm.getId()).orElseThrow().getStatus())
                .isEqualTo(BatchStatus.REVERSAL_PENDING);
    }

    @Test
    void reject_returnsBatchToConfirmed_andReceivablesStayCleared() {
        ClearingBatch cfm = confirmRing();
        reversalService.requestReversal(cfm.getId(), "r", "x");
        ReversalRequest rejected = reversalService.rejectReversal(cfm.getId(), "主管", "依据有效");

        assertThat(rejected.getStatus()).isEqualTo(ReversalStatus.REJECTED);
        assertThat(batchRepo.findById(cfm.getId()).orElseThrow().getStatus())
                .isEqualTo(BatchStatus.CONFIRMED);
        assertThat(receivableRepo.findAll())
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReceivableStatus.CLEARED));
    }
}
