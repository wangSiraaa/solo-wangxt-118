package com.treasury.clearing.dto;

import java.math.BigDecimal;
import java.util.List;

/** 日终结算报表快照视图（含汇总行、批次贡献链、再开账审计）。 */
public record ClosingReportView(String id,
                                String settlementDate,
                                int reportVersion,
                                String status,
                                String closedBy,
                                String closedAt,
                                String reopenReason,
                                String createdByReopen,
                                int batchCount,
                                BigDecimal totalCashAmount,
                                List<LineView> lines,
                                List<ContributionView> contributions,
                                ReopenView reopen) {

    public record LineView(String id,
                           String agreementCode,
                           String clearingCurrency,
                           String entityCode,
                           BigDecimal netPosition,
                           BigDecimal grossReceivable,
                           BigDecimal grossPayable,
                           BigDecimal cashAmount,
                           int dischargeCount) {
    }

    public record ContributionView(String id,
                                   String batchId,
                                   String batchKind,
                                   String batchLabel,
                                   String agreementCode,
                                   String clearingCurrency,
                                   BigDecimal grossReceivable,
                                   BigDecimal grossPayable,
                                   BigDecimal netPosition,
                                   BigDecimal cashAmount,
                                   int dischargeCount,
                                   String contributionType) {
    }

    public record ReopenView(String id,
                             String settlementDate,
                             int fromReportVersion,
                             Integer newReportVersion,
                             String newReportId,
                             String status,
                             int requiredApprovals,
                             int approvalsReceived,
                             String thresholdAgreement,
                             String thresholdAmount,
                             String dayCashAmount,
                             String dayCashCurrency,
                             String reason,
                             String requestedBy,
                             String requestedAt,
                             String finalizedBy,
                             String processedAt,
                             Integer affectedBatchCount,
                             String rejectedBy,
                             String rejectedAt,
                             String rejectReason,
                             List<ReopenDecisionView> decisions) {
    }

    public record ReopenDecisionView(String id,
                                     int seq,
                                     String outcome,
                                     String approver,
                                     String comment,
                                     String decidedAt,
                                     String statusBefore,
                                     String statusAfter,
                                     String closingStatusBefore,
                                     String closingStatusAfter,
                                     String newReportId) {
    }
}
