package com.treasury.clearing.service;

import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.ReversalDecision;
import com.treasury.clearing.domain.ReversalRequest;
import com.treasury.clearing.dto.BatchView;
import com.treasury.clearing.dto.ReversalView;
import com.treasury.clearing.repo.ClearingBatchRepository;
import com.treasury.clearing.repo.ExcludedClaimRepository;
import com.treasury.clearing.repo.ReversalDecisionRepository;
import com.treasury.clearing.repo.ReversalRequestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/** 实体 → API 视图。为避免懒加载问题，在事务内一次性装配。 */
@Component
public class BatchViewMapper {

    private final ExcludedClaimRepository excludedRepo;
    private final ReversalRequestRepository reversalRepo;
    private final ClearingBatchRepository batchRepo;
    private final ReversalDecisionRepository decisionRepo;

    public BatchViewMapper(ExcludedClaimRepository excludedRepo,
                           ReversalRequestRepository reversalRepo,
                           ClearingBatchRepository batchRepo,
                           ReversalDecisionRepository decisionRepo) {
        this.excludedRepo = excludedRepo;
        this.reversalRepo = reversalRepo;
        this.batchRepo = batchRepo;
        this.decisionRepo = decisionRepo;
    }

    /** 按 id 在事务内重新加载后装配，避免调用方传入脱管实体导致懒加载失败。 */
    @Transactional(readOnly = true)
    public BatchView toViewById(String id) {
        return toView(batchRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("批次不存在: " + id)));
    }

    @Transactional(readOnly = true)
    public BatchView toView(ClearingBatch b) {
        List<BatchView.ExcludedView> excluded = excludedRepo.findByBatchIdOrderByIdAsc(b.getId())
                .stream()
                .map(e -> new BatchView.ExcludedView(
                        e.getReceivableId(), e.getInvoiceNo(),
                        e.getExclusionReason(), e.getDetail()))
                .toList();

        List<BatchView.GroupView> groups = b.getGroups().stream()
                .sorted(Comparator.comparing(g -> g.getId()))
                .map(g -> new BatchView.GroupView(
                        g.getId(), g.getAgreementCode(), g.getClearingCurrency(),
                        g.isCrossCurrency(), g.getClaimCount(), g.getGrossClaimsDisplay(),
                        g.getNetEntryCount(),
                        g.getPositions().stream()
                                .sorted(Comparator.comparing(p -> p.getEntityCode()))
                                .map(p -> new BatchView.PositionView(
                                        p.getEntityCode(), p.getGrossReceivable(),
                                        p.getGrossPayable(), p.getNetAmount()))
                                .toList(),
                        g.getEntries().stream()
                                .sorted(Comparator.comparing(e -> e.getId()))
                                .map(e -> new BatchView.EntryView(
                                        e.getType().name(), e.getFromEntity(), e.getToEntity(),
                                        e.getAmount(), e.getCurrency(), e.getDescription()))
                                .toList(),
                        g.getDischarges().stream()
                                .sorted(Comparator.comparing(d -> d.getId()))
                                .map(d -> new BatchView.DischargeView(
                                        d.getReceivableId(), d.getInvoiceNo(),
                                        d.getCreditorCode(), d.getDebtorCode(),
                                        d.getOriginalCurrency(), d.getOriginalAmount(),
                                        d.getConvertedAmount(), d.getSetoffAmount(),
                                        d.getPaymentAmount(), d.getFxRate()))
                                .toList(),
                        g.getRoundingLines().stream()
                                .sorted(Comparator.comparing(r -> r.getId()))
                                .map(r -> new BatchView.RoundingView(
                                        r.getLineType(), r.getEntityCode(), r.getCurrency(),
                                        r.getAmount(), r.getRefInvoiceNo(), r.getFxRate(),
                                        r.getRateTime(), r.getNote()))
                                .toList()))
                .toList();

        ReversalView reversal = reversalRepo.findByOriginalBatchId(b.getId())
                .map(this::toReversalView).orElse(null);

        return new BatchView(b.getId(), b.getVersion(), b.getLabel(), b.getStatus().name(),
                b.getKind().name(),
                b.getReversesBatchId(), b.getReversalBatchId(),
                b.getCreatedAt().toString(),
                b.getValuationTime().toString(),
                b.getConfirmedAt() != null ? b.getConfirmedAt().toString() : null,
                b.getReversedAt() != null ? b.getReversedAt().toString() : null,
                b.getReversalRequestedAt() != null ? b.getReversalRequestedAt().toString() : null,
                b.getOriginalClaimCount(), b.getResultingEntryCount(), b.getExcludedCount(),
                b.getCreatedBy(), reversal, groups, excluded);
    }

    private ReversalView toReversalView(ReversalRequest r) {
        List<ReversalView.DecisionView> decisions =
                decisionRepo.listByRequest(r.getId()).stream()
                        .sorted(Comparator.comparingInt(ReversalDecision::getSeq))
                        .map(d -> new ReversalView.DecisionView(
                                d.getId(), d.getSeq(), d.getOutcome().name(),
                                d.getApprover(), d.getComment(), ts(d.getDecidedAt()),
                                d.getStatusBefore().name(), d.getStatusAfter().name(),
                                d.getReversalBatchId()))
                        .toList();
        return new ReversalView(r.getId(), r.getOriginalBatchId(), r.getReversalBatchId(),
                r.getStatus().name(), r.getRequiredApprovals(), r.getApprovalsReceived(),
                r.getThresholdAgreement(), money(r.getThresholdAmount()),
                money(r.getGrossClearedAmount()), r.getGrossClearedCurrency(),
                r.getReason(), r.getRequestedBy(), ts(r.getRequestedAt()),
                r.getFinalizedBy(), ts(r.getProcessedAt()), r.getRestoredCount(),
                r.getRejectedBy(), ts(r.getRejectedAt()), r.getRejectReason(), decisions);
    }

    private static String money(BigDecimal v) {
        return v != null ? v.stripTrailingZeros().toPlainString() : null;
    }

    private static String ts(java.time.Instant t) {
        return t != null ? t.toString() : null;
    }
}
