package com.treasury.clearing.dto;

import java.util.List;

/** 撤销申请视图（门槛快照 + 完整决议链 + 冲正审计）。 */
public record ReversalView(String id,
                           String originalBatchId,
                           String reversalBatchId,
                           String status,
                           int requiredApprovals,
                           int approvalsReceived,
                           String thresholdAgreement,
                           String thresholdAmount,
                           String grossClearedAmount,
                           String grossClearedCurrency,
                           String reason,
                           String requestedBy,
                           String requestedAt,
                           String finalizedBy,
                           String processedAt,
                           Integer restoredCount,
                           String rejectedBy,
                           String rejectedAt,
                           String rejectReason,
                           List<DecisionView> decisions) {

    public record DecisionView(String id,
                               int seq,
                               String outcome,
                               String approver,
                               String comment,
                               String decidedAt,
                               String statusBefore,
                               String statusAfter,
                               String reversalBatchId) {
    }
}
