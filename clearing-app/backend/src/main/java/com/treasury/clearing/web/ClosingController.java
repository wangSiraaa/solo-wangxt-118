package com.treasury.clearing.web;

import com.treasury.clearing.domain.ClosingReport;
import com.treasury.clearing.domain.ReopenDecision;
import com.treasury.clearing.domain.ReopenRequest;
import com.treasury.clearing.dto.ClosingReportView;
import com.treasury.clearing.service.ClosingService;
import com.treasury.clearing.service.ClosingViewMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 日终关账与合规再开账 API。
 * POST /api/closing/{date}/close           对结算日唯一关账
 * POST /api/closing/{date}/reopen-request  对已关账日发起再开账申请（四眼）
 * POST /api/closing/reopen/{requestId}/decision  提交再开账决议
 * GET  /api/closing/{date}                 该日全部报表版本（含汇总/贡献/再开账审计）
 */
@RestController
@RequestMapping("/api/closing")
public class ClosingController {

    private final ClosingService closingService;
    private final ClosingViewMapper mapper;

    public ClosingController(ClosingService closingService, ClosingViewMapper mapper) {
        this.closingService = closingService;
        this.mapper = mapper;
    }

    @GetMapping("/{date}")
    public List<ClosingReportView> list(@PathVariable String date) {
        return mapper.listByDate(LocalDate.parse(date));
    }

    @PostMapping("/{date}/close")
    public ResponseEntity<ClosingReportView> close(@PathVariable String date,
                                                    @RequestBody(required = false) CloseBody body) {
        String by = body != null ? body.closedBy() : null;
        ClosingReport report = closingService.close(LocalDate.parse(date), by);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toViewById(report.getId()));
    }

    @PostMapping("/{date}/reopen-request")
    public ResponseEntity<ClosingReportView> reopen(@PathVariable String date,
                                                     @RequestBody(required = false) ReopenBody body) {
        String reason = body != null ? body.reason() : null;
        String by = body != null ? body.requestedBy() : null;
        ReopenRequest req = closingService.requestReopen(LocalDate.parse(date), reason, by);
        ClosingReport current = closingService.listReports(LocalDate.parse(date)).stream()
                .filter(r -> r.getStatus() == com.treasury.clearing.domain.ClosingStatus.REOPEN_PENDING)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("再开账状态缺失"));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toViewById(current.getId()));
    }

    @PostMapping("/reopen/{requestId}/decision")
    public ResponseEntity<ClosingReportView> decision(@PathVariable String requestId,
                                                       @RequestBody(required = false) DecisionBody body) {
        ReopenDecision.Outcome outcome = body != null && body.outcome() != null
                ? ReopenDecision.Outcome.valueOf(body.outcome()) : null;
        ReopenRequest req = closingService.decide(requestId,
                body != null ? body.approver() : null,
                body != null ? body.comment() : null, outcome);
        LocalDate date = req.getSettlementDate();
        // 末审通过后返回新 CLOSED 版本；中途/驳回返回当前有效报表
        String targetId = req.getNewReportId();
        ClosingReport target = closingService.listReports(date).stream()
                .filter(r -> targetId != null ? r.getId().equals(targetId)
                        : r.getStatus() != com.treasury.clearing.domain.ClosingStatus.SUPERSEDED)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("关账报表缺失"));
        boolean created = req.getNewReportId() != null && req.getStatus()
                == com.treasury.clearing.domain.ReopenStatus.PROCESSED;
        return (created ? ResponseEntity.status(HttpStatus.CREATED) : ResponseEntity.ok())
                .body(mapper.toViewById(target.getId()));
    }

    public record CloseBody(String closedBy) {
    }

    public record ReopenBody(String reason, String requestedBy) {
    }

    public record DecisionBody(String approver, String comment, String outcome) {
    }
}
