package com.treasury.clearing.service;

import com.treasury.clearing.domain.AgreementParty;
import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.FxRate;
import com.treasury.clearing.domain.LegalEntity;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.domain.ReceivableStatus;
import com.treasury.clearing.dto.BatchView;
import com.treasury.clearing.repo.AgreementPartyRepository;
import com.treasury.clearing.repo.ClearingBatchRepository;
import com.treasury.clearing.repo.ExcludedClaimRepository;
import com.treasury.clearing.repo.FxRateRepository;
import com.treasury.clearing.repo.LegalEntityRepository;
import com.treasury.clearing.repo.NettingAgreementRepository;
import com.treasury.clearing.repo.ReceivableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BalanceConservationIntegrationTest {

    @Autowired private TrialService trialService;
    @Autowired private BatchViewMapper mapper;
    @Autowired private ClearingBatchRepository batchRepo;
    @Autowired private ExcludedClaimRepository excludedRepo;
    @Autowired private ReceivableRepository receivableRepo;
    @Autowired private LegalEntityRepository entityRepo;
    @Autowired private NettingAgreementRepository agreementRepo;
    @Autowired private AgreementPartyRepository partyRepo;
    @Autowired private FxRateRepository fxRepo;
    @Autowired private com.treasury.clearing.repo.ReversalRequestRepository requestRepo;
    @Autowired private com.treasury.clearing.repo.ReversalDecisionRepository decisionRepo;
    @Autowired private com.treasury.clearing.repo.InvoiceCorrectionEventRepository corrEventRepo;
    @Autowired private com.treasury.clearing.repo.AdjustmentDecisionRepository adjDecisionRepo;
    @Autowired private com.treasury.clearing.repo.AdjustmentRequestRepository adjRequestRepo;

    @Autowired private IntegrationTestCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.cleanAll();
        excludedRepo.deleteAll();
        corrEventRepo.deleteAll();
        adjDecisionRepo.deleteAll();
        adjRequestRepo.deleteAll();
        decisionRepo.deleteAll();
        requestRepo.deleteAll();
        batchRepo.deleteAll();
        receivableRepo.deleteAll();
        fxRepo.deleteAll();
        partyRepo.deleteAll();
        agreementRepo.deleteAll();
        entityRepo.deleteAll();

        for (String e : new String[]{"A", "B", "C", "D", "E", "F"}) {
            entityRepo.save(new LegalEntity(e, e));
        }
        agreementRepo.save(new NettingAgreement("NA", "同币种", false, null, null));
        agreementRepo.save(new NettingAgreement("XCCY", "跨币种", true, "USD", "F"));
        for (String e : new String[]{"A", "B", "C"}) {
            partyRepo.save(new AgreementParty("NA", e));
        }
        for (String e : new String[]{"D", "E", "F"}) {
            partyRepo.save(new AgreementParty("XCCY", e));
        }
        fxRepo.save(new FxRate("fx1", "EUR", "USD", new BigDecimal("1.0850000000"),
                Instant.parse("2026-09-15T09:30:00Z"), "test"));

        LocalDate d = LocalDate.of(2026, 8, 1);
        receivableRepo.save(new Receivable("c1", "I1", "A", "B", "CNY",
                new BigDecimal("1000000.00"), d, "NA", false, false, ReceivableStatus.ACTIVE));
        receivableRepo.save(new Receivable("c2", "I2", "B", "C", "CNY",
                new BigDecimal("1000000.00"), d, "NA", false, false, ReceivableStatus.ACTIVE));
        receivableRepo.save(new Receivable("c3", "I3", "C", "A", "CNY",
                new BigDecimal("1000000.00"), d, "NA", false, false, ReceivableStatus.ACTIVE));
        receivableRepo.save(new Receivable("x1", "IX1", "D", "E", "EUR",
                new BigDecimal("200.01"), d, "XCCY", false, false, ReceivableStatus.ACTIVE));
        receivableRepo.save(new Receivable("x2", "IX2", "E", "F", "USD",
                new BigDecimal("300.00"), d, "XCCY", false, false, ReceivableStatus.ACTIVE));
        receivableRepo.save(new Receivable("x3", "IX3", "F", "D", "USD",
                new BigDecimal("217.01"), d, "XCCY", false, false, ReceivableStatus.ACTIVE));
    }

    /**
     * 端到端守恒：每个清算组内
     *  - 净头寸合计为 0；
     *  - 每张发票 setoff+payment=折算额；
     *  - 按发票重算的毛应收-毛应付与净头寸逐主体一致；
     *  - 跨币种尾差行合计为 0（逐笔 + 承担方归集），且现金指令不含尾差。
     */
    @Test
    void everyGroupConservesBalances_andRoundingSumsToZero() {
        ClearingBatch sim = trialService.runTrial("守恒试算",
                java.time.Instant.parse("2026-09-15T10:00:00Z"), "tester", false);
        BatchView v = mapper.toView(sim);

        assertThat(v.groups()).isNotEmpty();
        for (BatchView.GroupView g : v.groups()) {
            BigDecimal netSum = g.positions().stream()
                    .map(p -> p.netAmount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(netSum).as("组 %s 净头寸合计", g.id()).isEqualByComparingTo("0");

            Map<String, BigDecimal> recv = new HashMap<>();
            Map<String, BigDecimal> pay = new HashMap<>();
            for (var d : g.discharges()) {
                assertThat(d.setoffAmount().add(d.paymentAmount()))
                        .as("发票 %s 互抵+付款=折算额", d.invoiceNo())
                        .isEqualByComparingTo(d.convertedAmount());
                recv.merge(d.creditorCode(), d.convertedAmount(), BigDecimal::add);
                pay.merge(d.debtorCode(), d.convertedAmount(), BigDecimal::add);
            }
            for (var p : g.positions()) {
                BigDecimal recomputed = recv.getOrDefault(p.entityCode(), BigDecimal.ZERO)
                        .subtract(pay.getOrDefault(p.entityCode(), BigDecimal.ZERO));
                assertThat(p.netAmount())
                        .as("组 %s 主体 %s 净头寸与发票重算一致", g.id(), p.entityCode())
                        .isEqualByComparingTo(recomputed);
            }

            if (g.crossCurrency()) {
                BigDecimal roundingSum = g.rounding().stream()
                        .map(BatchView.RoundingView::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .setScale(6, java.math.RoundingMode.HALF_UP);
                assertThat(roundingSum).as("跨币种尾差零和").isEqualByComparingTo("0");
                assertThat(g.entries()).noneMatch(e -> e.type().equals("ROUNDING"));
            }
        }

        // 清理，避免影响同 JVM 内其它测试
        excludedRepo.deleteAll();
        batchRepo.delete(sim);
    }
}
