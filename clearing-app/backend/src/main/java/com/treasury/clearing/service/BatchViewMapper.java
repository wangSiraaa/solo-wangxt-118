package com.treasury.clearing.service;

import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.dto.BatchView;
import com.treasury.clearing.repo.ExcludedClaimRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/** 实体 → API 视图。为避免懒加载问题，在事务内一次性装配。 */
@Component
public class BatchViewMapper {

    private final ExcludedClaimRepository excludedRepo;

    public BatchViewMapper(ExcludedClaimRepository excludedRepo) {
        this.excludedRepo = excludedRepo;
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

        return new BatchView(b.getId(), b.getLabel(), b.getStatus().name(),
                b.getCreatedAt().toString(),
                b.getValuationTime().toString(),
                b.getConfirmedAt() != null ? b.getConfirmedAt().toString() : null,
                b.getOriginalClaimCount(), b.getResultingEntryCount(), b.getExcludedCount(),
                b.getCreatedBy(), groups, excluded);
    }
}
