package com.treasury.clearing.service;

import com.treasury.clearing.repo.AdjustmentDecisionRepository;
import com.treasury.clearing.repo.AdjustmentRequestRepository;
import com.treasury.clearing.repo.ClosingContributionRepository;
import com.treasury.clearing.repo.ClosingReportLineRepository;
import com.treasury.clearing.repo.ClosingReportRepository;
import com.treasury.clearing.repo.DayLockRepository;
import com.treasury.clearing.repo.ExcludedClaimRepository;
import com.treasury.clearing.repo.InvoiceCorrectionEventRepository;
import com.treasury.clearing.repo.ReopenDecisionRepository;
import com.treasury.clearing.repo.ReopenRequestRepository;
import com.treasury.clearing.repo.ReversalDecisionRepository;
import com.treasury.clearing.repo.ReversalRequestRepository;
import com.treasury.clearing.repo.SettlementDayGateRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 集成测试辅助：按外键依赖顺序清空全部业务表。
 * H2 内存库在同一 SpringContext 内跨测试类共享，需要在每个用例前彻底清理，
 * 避免“今天已关账”等状态泄漏到其它测试类。
 */
@Component
public class IntegrationTestCleaner {

    private final ExcludedClaimRepository excludedRepo;
    private final ReversalDecisionRepository reversalDecisionRepo;
    private final ReversalRequestRepository reversalRepo;
    private final AdjustmentDecisionRepository adjustmentDecisionRepo;
    private final AdjustmentRequestRepository adjustmentRepo;
    private final InvoiceCorrectionEventRepository eventRepo;
    private final ReopenDecisionRepository reopenDecisionRepo;
    private final ReopenRequestRepository reopenRepo;
    private final ClosingContributionRepository contributionRepo;
    private final ClosingReportLineRepository lineRepo;
    private final ClosingReportRepository reportRepo;
    private final DayLockRepository dayLockRepo;
    private final SettlementDayGateRepository dayGateRepo;

    public IntegrationTestCleaner(ExcludedClaimRepository excludedRepo,
                                  ReversalDecisionRepository reversalDecisionRepo,
                                  ReversalRequestRepository reversalRepo,
                                  AdjustmentDecisionRepository adjustmentDecisionRepo,
                                  AdjustmentRequestRepository adjustmentRepo,
                                  InvoiceCorrectionEventRepository eventRepo,
                                  ReopenDecisionRepository reopenDecisionRepo,
                                  ReopenRequestRepository reopenRepo,
                                  ClosingContributionRepository contributionRepo,
                                  ClosingReportLineRepository lineRepo,
                                  ClosingReportRepository reportRepo,
                                  DayLockRepository dayLockRepo,
                                  SettlementDayGateRepository dayGateRepo) {
        this.excludedRepo = excludedRepo;
        this.reversalDecisionRepo = reversalDecisionRepo;
        this.reversalRepo = reversalRepo;
        this.adjustmentDecisionRepo = adjustmentDecisionRepo;
        this.adjustmentRepo = adjustmentRepo;
        this.eventRepo = eventRepo;
        this.reopenDecisionRepo = reopenDecisionRepo;
        this.reopenRepo = reopenRepo;
        this.contributionRepo = contributionRepo;
        this.lineRepo = lineRepo;
        this.reportRepo = reportRepo;
        this.dayLockRepo = dayLockRepo;
        this.dayGateRepo = dayGateRepo;
    }

    /**
     * 仅清理由本类引入的、跨测试类可能泄漏的“审批/关账”状态（申请/决议/事件/快照/日锁/排除）。
     * 批次、债权、协议等仍由各测试 setUp 用 JPA 级联 deleteAll() 自行清理，
     * 这里不能用批量 DELETE 直删 clearing_batch（会绕过级联、触发子表外键约束）。
     */
    @Transactional
    public void cleanAll() {
        excludedRepo.deleteAllInBatch();
        reopenDecisionRepo.deleteAllInBatch();
        reopenRepo.deleteAllInBatch();
        adjustmentDecisionRepo.deleteAllInBatch();
        eventRepo.deleteAllInBatch();
        adjustmentRepo.deleteAllInBatch();
        reversalDecisionRepo.deleteAllInBatch();
        reversalRepo.deleteAllInBatch();
        contributionRepo.deleteAllInBatch();
        lineRepo.deleteAllInBatch();
        reportRepo.deleteAllInBatch();
        dayGateRepo.deleteAllInBatch();
        dayLockRepo.deleteAllInBatch();
    }
}
