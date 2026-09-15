package com.treasury.clearing.dto;

/** 撤销申请视图（完整审计：申请人/审批人/时间/原因/冲正批次/恢复张数）。 */
public record ReversalView(String id,
                           String originalBatchId,
                           String reversalBatchId,
                           String status,
                           String reason,
                           String requestedBy,
                           String requestedAt,
                           String approvedBy,
                           String approvedAt,
                           String processedAt,
                           Integer restoredCount,
                           String rejectedBy,
                           String rejectedAt,
                           String rejectReason) {
}
