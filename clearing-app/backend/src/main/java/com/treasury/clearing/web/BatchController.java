package com.treasury.clearing.web;

import com.treasury.clearing.domain.AdjustmentDecision;
import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.ReversalDecision;
import com.treasury.clearing.domain.ReversalRequest;
import com.treasury.clearing.dto.AdjustmentView;
import com.treasury.clearing.dto.BatchView;
import com.treasury.clearing.dto.ReversalView;
import com.treasury.clearing.dto.TrialRequest;
import com.treasury.clearing.service.AdjustmentService;
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

import java.math.BigDecimal;
import java.util.List;

/**
 * 清算批次 API（含撤销四眼审批与差额更正四眼审批）。
 * 不提供任何银行划款接口；差额更正不清偿/恢复原始债权。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final TrialService trialService;
    private final ReversalService reversalService;
    private final AdjustmentService adjustmentService;
    private final BatchQueryService queryService;
    private final BatchViewMapper mapper;

    public BatchController(TrialService trialService, ReversalService reversalService,
                           AdjustmentService adjustmentService,
                           BatchQueryService queryService, BatchViewMapper mapper) {
        this.trialService = trialService;
        this.reversalService = reversalService;
        this.adjustmentService = adjustmentService;
        this.queryService = queryService;
        this.mapper = mapper;
    }

    @GetMapping
    public List<BatchSummaryView> list() {
        return queryService.list().stream()
                .map(b -> new BatchSummaryView(b.getId(), b.getVersion(), b.getLabel(),
                        b.getStatus().name(), b.getKind().name(),
                        b.getReversesBatchId(), b.getReversalBatchId(),
                        b.getAdjustsBatchId(), b.getAdjustmentBatchId(),
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

    // ---- 撤销 ----

    @PostMapping("/{id}/reversal-request")
    public ResponseEntity<ReversalView> requestReversal(@PathVariable String id,
                                                        @RequestBody(required = false)
                                                        ReversalRequestBody body) {
        String reason = body != null ? body.reason() : null;
        String by = body != null ? body.requestedBy() : null;
        reversalService.requestReversal(id, reason, by);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toViewById(id).reversal());
    }

    @PostMapping("/{id}/reversal/decision")
    public ResponseEntity<BatchView> reversalDecision(@PathVariable String id,
                                                       @RequestBody(required = false) DecisionBody body) {
        ReversalDecision.Outcome outcome = body != null && body.outcome() != null
                ? ReversalDecision.Outcome.valueOf(body.outcome()) : null;
        reversalService.decide(id, body != null ? body.approver() : null,
                body != null ? body.comment() : null, outcome);
        BatchView origin = mapper.toViewById(id);
        if (origin.reversal() != null && origin.reversal().reversalBatchId() != null) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(mapper.toViewById(origin.reversal().reversalBatchId()));
        }
        return ResponseEntity.ok(origin);
    }

    @GetMapping("/{id}/reversal")
    public ReversalView reversal(@PathVariable String id) {
        reversalService.getRequestForBatch(id);
        return mapper.toViewById(id).reversal();
    }

    // ---- 差额更正 ----

    @PostMapping("/{id}/adjustment-request")
    public ResponseEntity<AdjustmentView> requestAdjustment(@PathVariable String id,
                                                            @RequestBody(required = false)
                                                            AdjustmentRequestBody body) {
        if (body == null || body.corrections() == null || body.corrections().isEmpty()) {
            throw new IllegalArgumentException("corrections 不能为空");
        }
        List<AdjustmentService.CorrectionSpec> specs = body.corrections().stream()
                .map(c -> new AdjustmentService.CorrectionSpec(
                        c.receivableId(), c.newAmount(), c.newAgreementCode(),
                        c.reason(), c.effectiveScope()))
                .toList();
        adjustmentService.request(id, specs, body.reason(), body.requestedBy());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toViewById(id).adjustment());
    }

    @PostMapping("/{id}/adjustment/decision")
    public ResponseEntity<BatchView> adjustmentDecision(@PathVariable String id,
                                                         @RequestBody(required = false) DecisionBody body) {
        AdjustmentDecision.Outcome outcome = body != null && body.outcome() != null
                ? AdjustmentDecision.Outcome.valueOf(body.outcome()) : null;
        adjustmentService.decide(id, body != null ? body.approver() : null,
                body != null ? body.comment() : null, outcome);
        BatchView origin = mapper.toViewById(id);
        if (origin.adjustment() != null && origin.adjustment().adjustmentBatchId() != null) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(mapper.toViewById(origin.adjustment().adjustmentBatchId()));
        }
        return ResponseEntity.ok(origin);
    }

    @GetMapping("/{id}/adjustment")
    public AdjustmentView adjustment(@PathVariable String id) {
        adjustmentService.getForBatch(id);
        return mapper.toViewById(id).adjustment();
    }

    public record BatchSummaryView(String id, long version, String label, String status,
                                   String kind, String reversesBatchId, String reversalBatchId,
                                   String adjustsBatchId, String adjustmentBatchId,
                                   String createdAt, String confirmedAt,
                                   int originalClaimCount, int resultingEntryCount,
                                   int excludedCount, String createdBy) {
    }

    public record ConfirmRequest(String createdBy) {
    }

    public record ReversalRequestBody(String reason, String requestedBy) {
    }

    public record DecisionBody(String approver, String comment, String outcome) {
    }

    public record AdjustmentRequestBody(String reason, String requestedBy,
                                        List<CorrectionBody> corrections) {
    }

    public record CorrectionBody(String receivableId, BigDecimal newAmount,
                                 String newAgreementCode, String reason, String effectiveScope) {
    }
}
