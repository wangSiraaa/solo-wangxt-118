package com.treasury.clearing.web;

import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.dto.BatchView;
import com.treasury.clearing.dto.TrialRequest;
import com.treasury.clearing.service.BatchQueryService;
import com.treasury.clearing.service.BatchViewMapper;
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
 * POST /api/batches/trial      试算（不动任何原始债权）
 * POST /api/batches/{id}/confirm  确认试算方案（重新按当前数据计算并生效）
 * 明确不提供任何银行划款接口。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final TrialService trialService;
    private final BatchQueryService queryService;
    private final BatchViewMapper mapper;

    public BatchController(TrialService trialService, BatchQueryService queryService,
                           BatchViewMapper mapper) {
        this.trialService = trialService;
        this.queryService = queryService;
        this.mapper = mapper;
    }

    @GetMapping
    public List<BatchSummaryView> list() {
        return queryService.list().stream()
                .map(b -> new BatchSummaryView(b.getId(), b.getLabel(), b.getStatus().name(),
                        b.getCreatedAt().toString(),
                        b.getConfirmedAt() != null ? b.getConfirmedAt().toString() : null,
                        b.getOriginalClaimCount(), b.getResultingEntryCount(),
                        b.getExcludedCount(), b.getCreatedBy()))
                .toList();
    }

    @GetMapping("/{id}")
    public BatchView get(@PathVariable String id) {
        return mapper.toView(queryService.get(id));
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

    public record BatchSummaryView(String id, String label, String status, String createdAt,
                                   String confirmedAt, int originalClaimCount,
                                   int resultingEntryCount, int excludedCount, String createdBy) {
    }

    public record ConfirmRequest(String createdBy) {
    }
}
