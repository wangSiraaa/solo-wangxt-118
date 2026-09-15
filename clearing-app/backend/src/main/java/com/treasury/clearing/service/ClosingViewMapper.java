package com.treasury.clearing.service;

import com.treasury.clearing.domain.ClosingContribution;
import com.treasury.clearing.domain.ClosingReport;
import com.treasury.clearing.domain.ClosingReportLine;
import com.treasury.clearing.domain.ReopenDecision;
import com.treasury.clearing.domain.ReopenRequest;
import com.treasury.clearing.dto.ClosingReportView;
import com.treasury.clearing.repo.ClosingContributionRepository;
import com.treasury.clearing.repo.ClosingReportLineRepository;
import com.treasury.clearing.repo.ClosingReportRepository;
import com.treasury.clearing.repo.ReopenDecisionRepository;
import com.treasury.clearing.repo.ReopenRequestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** 关账/再开账实体 → API 视图（在事务内装配，避免懒加载）。 */
@Component
public class ClosingViewMapper {

    private final ClosingReportRepository reportRepo;
    private final ClosingReportLineRepository lineRepo;
    private final ClosingContributionRepository contribRepo;
    private final ReopenRequestRepository reopenRepo;
    private final ReopenDecisionRepository reopenDecisionRepo;

    public ClosingViewMapper(ClosingReportRepository reportRepo,
                             ClosingReportLineRepository lineRepo,
                             ClosingContributionRepository contribRepo,
                             ReopenRequestRepository reopenRepo,
                             ReopenDecisionRepository reopenDecisionRepo) {
        this.reportRepo = reportRepo;
        this.lineRepo = lineRepo;
        this.contribRepo = contribRepo;
        this.reopenRepo = reopenRepo;
        this.reopenDecisionRepo = reopenDecisionRepo;
    }

    @Transactional(readOnly = true)
    public List<ClosingReportView> listByDate(LocalDate date) {
        return reportRepo.findBySettlementDateOrderByReportVersionDesc(date).stream()
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public ClosingReportView toViewById(String id) {
        return toView(reportRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("关账报表不存在: " + id)));
    }

    @Transactional(readOnly = true)
    public ClosingReportView toView(ClosingReport r) {
        List<ClosingReportView.LineView> lines =
                lineRepo.findByReportIdOrderByAgreementCodeAscClearingCurrencyAscEntityCodeAsc(r.getId())
                        .stream().map(this::line).toList();
        List<ClosingReportView.ContributionView> contribs =
                contribRepo.findByReportIdOrderByAgreementCodeAscClearingCurrencyAscBatchIdAsc(r.getId())
                        .stream().map(this::contrib).toList();
        // 取该结算日最新的再开账申请；v1 与新生成的 v2 都展示同一条决议链
        ClosingReportView.ReopenView reopen = reopenRepo.listByDate(r.getSettlementDate()).stream()
                .max(Comparator.comparing(ReopenRequest::getRequestedAt))
                .map(this::reopen).orElse(null);
        return new ClosingReportView(r.getId(), r.getSettlementDate().toString(),
                r.getReportVersion(), r.getStatus().name(), r.getClosedBy(),
                r.getClosedAt() != null ? r.getClosedAt().toString() : null,
                r.getReopenReason(), r.getCreatedByReopen(), r.getBatchCount(),
                r.getTotalCashAmount(), lines, contribs, reopen);
    }

    private ClosingReportView.LineView line(ClosingReportLine l) {
        return new ClosingReportView.LineView(l.getId(), l.getAgreementCode(),
                l.getClearingCurrency(), l.getEntityCode(), l.getNetPosition(),
                l.getGrossReceivable(), l.getGrossPayable(), l.getCashAmount(),
                l.getDischargeCount());
    }

    private ClosingReportView.ContributionView contrib(ClosingContribution c) {
        return new ClosingReportView.ContributionView(c.getId(), c.getBatchId(),
                c.getBatchKind(), c.getBatchLabel(), c.getAgreementCode(), c.getClearingCurrency(),
                c.getGrossReceivable(), c.getGrossPayable(), c.getNetPosition(),
                c.getCashAmount(), c.getDischargeCount(), c.getContributionType().name());
    }

    private ClosingReportView.ReopenView reopen(ReopenRequest x) {
        List<ClosingReportView.ReopenDecisionView> decisions =
                reopenDecisionRepo.listByRequest(x.getId()).stream()
                        .sorted(Comparator.comparingInt(ReopenDecision::getSeq))
                        .map(d -> new ClosingReportView.ReopenDecisionView(
                                d.getId(), d.getSeq(), d.getOutcome().name(), d.getApprover(),
                                d.getComment(), d.getDecidedAt().toString(),
                                d.getStatusBefore().name(), d.getStatusAfter().name(),
                                d.getClosingStatusBefore() != null ? d.getClosingStatusBefore().name() : null,
                                d.getClosingStatusAfter() != null ? d.getClosingStatusAfter().name() : null,
                                d.getNewReportId()))
                        .toList();
        return new ClosingReportView.ReopenView(x.getId(), x.getSettlementDate().toString(),
                x.getFromReportVersion(), x.getNewReportVersion(), x.getNewReportId(),
                x.getStatus().name(), x.getRequiredApprovals(), x.getApprovalsReceived(),
                x.getThresholdAgreement(), money(x.getThresholdAmount()),
                money(x.getDayCashAmount()), x.getDayCashCurrency(),
                x.getReason(), x.getRequestedBy(), ts(x.getRequestedAt()),
                x.getFinalizedBy(), ts(x.getProcessedAt()), x.getAffectedBatchCount(),
                x.getRejectedBy(), ts(x.getRejectedAt()), x.getRejectReason(), decisions);
    }

    private static String money(BigDecimal v) {
        return v != null ? v.stripTrailingZeros().toPlainString() : null;
    }

    private static String ts(java.time.Instant t) {
        return t != null ? t.toString() : null;
    }
}
