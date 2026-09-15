package com.treasury.clearing.web;

import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.ReversalDecision;
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
 * POST /api/batches/trial            试算（不动任何原始债权）
 * POST /api/batches/{id}/confirm     确认试算方案
 * POST /api/batches/{id}/reversal-request        对 CONFIRMED 批次发起撤销申请
 * POST /api/batches/{id}/reversal/decision       提交一条四眼审批决议（APPROVE/REJECT）
 * GET  /api/batches/{id}/reversal                撤销申请 + 完整决议链审计
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
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toViewById(id).reversal());
    }

    /** 分级四眼：提交一条不可变审批决议；凑满门槛名额才在同事务冲正。 */
    @PostMapping("/{id}/reversal/decision")
    public ResponseEntity<BatchView> decision(@PathVariable String id,
                                              @RequestBody(required = false) DecisionBody body) {
        ReversalDecision.Outcome outcome = body != null && body.outcome() != null
                ? ReversalDecision.Outcome.valueOf(body.outcome())
                : null;
        String approver = body != null ? body.approver() : null;
        String comment = body != null ? body.comment() : null;
        reversalService.decide(id, approver, comment, outcome);
        // 审批通过且凑满名额返回冲正批次；中途通过/驳回返回原批次最新视图
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

    public record DecisionBody(String approver, String comment, String outcome) {
    }
}
