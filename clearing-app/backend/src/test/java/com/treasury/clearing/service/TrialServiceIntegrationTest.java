package com.treasury.clearing.service;

import com.treasury.clearing.calc.NettingEngine;
import com.treasury.clearing.domain.AgreementParty;
import com.treasury.clearing.domain.BatchStatus;
import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.FxRate;
import com.treasury.clearing.domain.LegalEntity;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.domain.ReceivableStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TrialServiceIntegrationTest {

    @Autowired private ReceivableRepository receivableRepo;
    @Autowired private LegalEntityRepository entityRepo;
    @Autowired private NettingAgreementRepository agreementRepo;
    @Autowired private AgreementPartyRepository partyRepo;
    @Autowired private FxRateRepository fxRepo;
    @Autowired private ClearingBatchRepository batchRepo;
    @Autowired private ExcludedClaimRepository excludedRepo;
    @Autowired private com.treasury.clearing.repo.ReversalRequestRepository requestRepo;
    @Autowired private com.treasury.clearing.repo.ReversalDecisionRepository decisionRepo;
    @Autowired private TrialService trialService;
    @Autowired private NettingEngine engine;

    @BeforeEach
    void setUp() {
        excludedRepo.deleteAll();
        decisionRepo.deleteAll();
        requestRepo.deleteAll();
        batchRepo.deleteAll();
        receivableRepo.deleteAll();
        fxRepo.deleteAll();
        partyRepo.deleteAll();
        agreementRepo.deleteAll();
        entityRepo.deleteAll();

        entityRepo.save(new LegalEntity("A", "甲"));
        entityRepo.save(new LegalEntity("B", "乙"));
        entityRepo.save(new LegalEntity("C", "丙"));
        entityRepo.save(new LegalEntity("G", "庚"));
        entityRepo.save(new LegalEntity("H", "辛"));

        agreementRepo.save(new NettingAgreement("NA", "多边协议", false, null, null));
        partyRepo.save(new AgreementParty("NA", "A"));
        partyRepo.save(new AgreementParty("NA", "B"));
        partyRepo.save(new AgreementParty("NA", "C"));

        receivableRepo.save(new Receivable("r1", "INV-1", "A", "B", "CNY",
                new BigDecimal("1000000.00"), LocalDate.of(2026, 8, 1),
                "NA", false, false, ReceivableStatus.ACTIVE));
        receivableRepo.save(new Receivable("r2", "INV-2", "B", "C", "CNY",
                new BigDecimal("1000000.00"), LocalDate.of(2026, 8, 2),
                "NA", false, false, ReceivableStatus.ACTIVE));
        receivableRepo.save(new Receivable("r3", "INV-3", "C", "A", "CNY",
                new BigDecimal("1000000.00"), LocalDate.of(2026, 8, 3),
                "NA", false, false, ReceivableStatus.ACTIVE));
        // 质押 + 争议
        receivableRepo.save(new Receivable("r4", "INV-4", "A", "B", "CNY",
                new BigDecimal("80000.00"), LocalDate.of(2026, 8, 4),
                "NA", true, false, ReceivableStatus.ACTIVE));
        receivableRepo.save(new Receivable("r5", "INV-5", "B", "A", "CNY",
                new BigDecimal("80000.00"), LocalDate.of(2026, 8, 5),
                "NA", false, true, ReceivableStatus.ACTIVE));
        // 无协议
        receivableRepo.save(new Receivable("r6", "INV-6", "G", "H", "HKD",
                new BigDecimal("50000.00"), LocalDate.of(2026, 8, 6),
                null, false, false, ReceivableStatus.ACTIVE));
    }

    @Test
    void trial_collapsesRingAndKeepsSimulationNonDestructive() {
        ClearingBatch sim = trialService.runTrial("试算1", Instant.parse("2026-09-15T09:00:00Z"),
                "tester", false);

        assertThat(sim.getStatus()).isEqualTo(BatchStatus.SIMULATED);
        // 6 张原始债权；环 3 张变成 1 条 0 金额互抵闭环；其余 3 张被排除
        assertThat(sim.getOriginalClaimCount()).isEqualTo(6);
        assertThat(sim.getResultingEntryCount()).isEqualTo(1);
        assertThat(sim.getExcludedCount()).isEqualTo(3);

        var group = sim.getGroups().get(0);
        assertThat(group.getEntries()).hasSize(1);
        assertThat(group.getEntries().get(0).getType().name()).isEqualTo("SETOFF");
        assertThat(group.getEntries().get(0).getAmount()).isEqualByComparingTo("0");
        assertThat(group.getDischarges()).hasSize(3);

        var exclusions = excludedRepo.findByBatchIdOrderByIdAsc(sim.getId());
        assertThat(exclusions).extracting(e -> e.getExclusionReason())
            .containsExactlyInAnyOrder("PLEDGED", "DISPUTED", "NO_AGREEMENT");

        // 试算不改动任何原始债权
        assertThat(receivableRepo.findById("r1").orElseThrow().getStatus())
                .isEqualTo(ReceivableStatus.ACTIVE);
        // 原始金额不变
        assertThat(receivableRepo.findById("r6").orElseThrow().getAmount())
                .isEqualByComparingTo("50000.00");
    }

    @Test
    void confirm_changesOnlyStatusAndReproducesSameNumbers() {
        ClearingBatch sim = trialService.runTrial("试算2", null, "tester", false);
        ClearingBatch cfm = trialService.confirmFromSimulation(sim.getId(), "approver");

        assertThat(cfm.getStatus()).isEqualTo(BatchStatus.CONFIRMED);
        assertThat(cfm.getResultingEntryCount()).isEqualTo(sim.getResultingEntryCount());

        // 环内发票标记 CLEARED；被排除的仍 ACTIVE，原始债务保留
        assertThat(receivableRepo.findById("r1").orElseThrow().getStatus())
                .isEqualTo(ReceivableStatus.CLEARED);
        assertThat(receivableRepo.findById("r4").orElseThrow().getStatus())
                .isEqualTo(ReceivableStatus.ACTIVE);
        assertThat(receivableRepo.findById("r5").orElseThrow().getStatus())
                .isEqualTo(ReceivableStatus.ACTIVE);
        assertThat(receivableRepo.findById("r6").orElseThrow().getStatus())
                .isEqualTo(ReceivableStatus.ACTIVE);

        // 已确认批次不能再次确认
        assertThatThrownBy(() -> trialService.confirmFromSimulation(cfm.getId(), "x"))
                .isInstanceOf(ClearingRuleException.class);
    }

    @Test
    void missingFxRate_failsWithRuleError_andNothingMarkedCleared() {
        agreementRepo.save(new NettingAgreement("XCCY", "跨币种", true, "USD", "B"));
        partyRepo.save(new AgreementParty("XCCY", "A"));
        partyRepo.save(new AgreementParty("XCCY", "B"));
        receivableRepo.save(new Receivable("x1", "INV-X", "A", "B", "EUR",
                new BigDecimal("100.00"), LocalDate.of(2026, 8, 7),
                "XCCY", false, false, ReceivableStatus.ACTIVE));

        assertThatThrownBy(() ->
                trialService.runTrial("缺汇率", Instant.parse("2026-09-15T09:00:00Z"),
                        "tester", false))
                .isInstanceOf(ClearingRuleException.class)
                .hasMessageContaining("EUR->USD");
        assertThat(receivableRepo.findById("x1").orElseThrow().getStatus())
                .isEqualTo(ReceivableStatus.ACTIVE);
    }
}
