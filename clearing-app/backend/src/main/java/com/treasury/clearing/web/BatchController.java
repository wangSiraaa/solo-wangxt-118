package com.treasury.clearing.web;

import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.ReversalRequest;
import com.treasury.clearing.dto.BatchView;
import com.treasury.clearing.dto.ReversalView;
import com.treasury.clearing.dto.TrialRequest;
import com.treasury.clearing.service.BatchQueryService;
import com.treasury.clearing.service.BatchViewMapper;
import com.treasury.clearing.service.ReversalService;
import com.treasury.clearing.service.TrialService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 清算批次 API。
 * POST /api/batches/trial         试算（不动任何原始债权）
 * POST /api/batches/{id}/confirm  确认试算方案（重新按当前数据计算并生效）
 * POST /api/batches/{id}/reversal-request  对 CONFIRMED 批次发起撤销申请
 * POST /api/batches/{id}/reversal/approve  审批通过 → 生成冲正批次、恢复债权
 * POST /api/batches/{id}/reversal/reject   审批驳回 → 原批次回到 CONFIRMED
 * GET  /api/batches/{id}/reversal          撤销申请审计视图
 * 明确不提供任何银行划款接口。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final TrialService trialService;
    private final ReversalService reversalService;
    private final BatchQueryService queryService;
    private final BatchViewMapper mapper;

    public BatchController(TrialService trialService, ReversalService reversalService,
                           BatchQueryService queryService, BatchViewMapper mapper) {
        this.trialService = trialService;
        this.reversalService = reversalService;
        this.queryService = queryService;
        this.mapper = mapper;
    }

    @GetMapping
    public List<BatchSummaryView> list() {
        return queryService.list().stream()
                .map(b -> new BatchSummaryView(b.getId(), b.getVersion(), b.getLabel(),
                        b.getStatus().name(), b.getKind().name(),
                        b.getReversesBatchId(), b.getReversalBatchId(),
                        b.getCreatedAt().toString(),
                        b.getConfirmedAt() != null ? b.getConfirmedAt().toString() : null,
                        b.getOriginalClaimCount(), b.getResultingEntryCount(),
                        b.getExcludedCount(), b.getCreatedBy()))
                .toList();
    }

    @GetMapping("/{id}")
    public BatchView get(@PathVariable String id) {
        return mapper.toViewById(id);
    }

    @PostMapping("/trial")
    public ResponseEntity<BatchView> trial(@RequestBody(required = false) TrialRequest req) {
        TrialRequest r = req != null ? req : new TrialRequest(null, null, null);
        ClearingBatch batch = trialService.runTrial(r.label(), r.valuationTime(),
                r.createdBy(), false);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toView(batch));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<BatchView> confirm(@PathVariable String id,
                                             @RequestBody(required = false) ConfirmRequest req) {
        String by = req != null ? req.createdBy() : null;
        ClearingBatch confirmed = trialService.confirmFromSimulation(id, by);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toView(confirmed));
    }

    @PostMapping("/{id}/reversal-request")
    public ResponseEntity<ReversalView> requestReversal(@PathVariable String id,
                                                        @RequestBody(required = false)
                                                        ReversalRequestBody body) {
        String reason = body != null ? body.reason() : null;
        String by = body != null ? body.requestedBy() : null;
        ReversalRequest request = reversalService.requestReversal(id, reason, by);
        return ResponseEntity.status(HttpStatus.CREATED).body(toReversalView(request));
    }

    @PostMapping("/{id}/reversal/approve")
    public ResponseEntity<BatchView> approve(@PathVariable String id,
                                             @RequestBody(required = false)
                                             ReversalDecisionBody body) {
        String by = body != null ? body.approvedBy() : null;
        ClearingBatch reversalBatch = reversalService.approveReversal(id, by);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toView(reversalBatch));
    }

    @PostMapping("/{id}/reversal/reject")
    public ResponseEntity<ReversalView> reject(@PathVariable String id,
                                               @RequestBody(required = false)
                                               ReversalDecisionBody body) {
        String by = body != null ? body.approvedBy() : null;
        String reason = body != null ? body.reason() : null;
        ReversalRequest request = reversalService.rejectReversal(id, by, reason);
        return ResponseEntity.ok(toReversalView(request));
    }

    @GetMapping("/{id}/reversal")
    public ReversalView reversal(@PathVariable String id) {
        return toReversalView(reversalService.getRequestForBatch(id));
    }

    private ReversalView toReversalView(ReversalRequest r) {
        return new ReversalView(r.getId(), r.getOriginalBatchId(), r.getReversalBatchId(),
                r.getStatus().name(), r.getReason(),
                r.getRequestedBy(), ts(r.getRequestedAt()),
                r.getApprovedBy(), ts(r.getApprovedAt()),
                ts(r.getProcessedAt()), r.getRestoredCount(),
                r.getRejectedBy(), ts(r.getRejectedAt()), r.getRejectReason());
    }

    private static String ts(java.time.Instant t) {
        return t != null ? t.toString() : null;
    }

    public record BatchSummaryView(String id, long version, String label, String status,
                                   String kind, String reversesBatchId, String reversalBatchId,
                                   String createdAt, String confirmedAt,
                                   int originalClaimCount, int resultingEntryCount,
                                   int excludedCount, String createdBy) {
    }

    public record ConfirmRequest(String createdBy) {
    }

    public record ReversalRequestBody(String reason, String requestedBy) {
    }

    public record ReversalDecisionBody(String approvedBy, String reason) {
    }
}
