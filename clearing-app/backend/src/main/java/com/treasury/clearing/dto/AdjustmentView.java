package com.treasury.clearing.dto;

import java.math.BigDecimal;
import java.util.List;

/** 差额更正申请视图：门槛快照 + 发票更正事件 + 决议链。 */
public record AdjustmentView(String id,
                             String originalBatchId,
                             String adjustmentBatchId,
                             String status,
                             int requiredApprovals,
                             int approvalsReceived,
                             String thresholdAgreement,
                             String thresholdAmount,
                             String deltaGrossAmount,
                             String deltaGrossCurrency,
                             String reason,
                             String requestedBy,
                             String requestedAt,
                             String finalizedBy,
                             String processedAt,
                             Integer eventCount,
                             String rejectedBy,
                             String rejectedAt,
                             String rejectReason,
                             List<EventView> events,
                             List<DecisionView> decisions) {

    public record EventView(String id,
                            int seq,
                            String receivableId,
                            String invoiceNo,
                            String correctedField,
                            BigDecimal oldAmount,
                            String oldCurrency,
                            String oldAgreementCode,
                            BigDecimal newAmount,
                            String newCurrency,
                            String newAgreementCode,
                            BigDecimal oldConverted,
                            BigDecimal newConverted,
                            BigDecimal deltaConverted,
                            String clearingCurrency,
                            String effectiveScope,
                            String reason,
                            String requestedBy,
                            String createdAt) {
    }

    public record DecisionView(String id,
                               int seq,
                               String outcome,
                               String approver,
                               String comment,
                               String decidedAt,
                               String statusBefore,
                               String statusAfter,
                               String adjustmentBatchId) {
    }
}
