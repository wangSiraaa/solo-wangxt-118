package com.treasury.clearing.service;

import com.treasury.clearing.domain.BatchKind;
import com.treasury.clearing.domain.BatchStatus;
import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.ClearingEntry;
import com.treasury.clearing.domain.ClearingGroup;
import com.treasury.clearing.domain.ConflictException;
import com.treasury.clearing.domain.InvoiceDischarge;
import com.treasury.clearing.domain.NetPosition;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.domain.PaymentType;
import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.domain.ReversalDecision;
import com.treasury.clearing.domain.ReversalRequest;
import com.treasury.clearing.domain.ReversalStatus;
import com.treasury.clearing.domain.RoundingLine;
import com.treasury.clearing.repo.ClearingBatchRepository;
import com.treasury.clearing.repo.AdjustmentRequestRepository;
import com.treasury.clearing.repo.NettingAgreementRepository;
import com.treasury.clearing.repo.ReceivableRepository;
import com.treasury.clearing.repo.ReversalDecisionRepository;
import com.treasury.clearing.repo.ReversalRequestRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * 确认方案撤销与冲正（分级四眼审批）。
 *
 * <p>状态机：{@code CONFIRMED → REVERSAL_PENDING → REVERSED}（驳回回到 CONFIRMED）；
 * 申请：{@code REQUESTED → PARTIALLY_APPROVED(双审一审后) → PROCESSED}，任一决议驳回即 REJECTED。
 *
 * <p>审批名额在发起时按协议门槛快照（1 一审 / 2 双审）。每次审批只<b>新增</b>不可修改的
 * {@link ReversalDecision}，凑满名额的那条决议在同一事务内生成唯一冲正批次、恢复债权。
 *
 * <p>并发与幂等：
 * <ul>
 *   <li>批次/申请/决议/债权均有乐观锁；决策处理先锁申请行，再锁决议行与相关债权行；</li>
 *   <li>同一申请下审批人唯一（DB 唯一索引 + 加锁预查），申请人不得是审批人；</li>
 *   <li>两个审批人并发抢最后名额：持锁者提交 PROCESSED 并冲正，等待者随后读到 PROCESSED 直接 409，
 *       不多记决议、不重复冲正、不动债权；</li>
 *   <li>双审下首审决议独立提交；后续失败/重启后重试，同一审批人重复提交 409（不重复占名额），
 *       由第二审批人安全继续；末审的“决议+冲正+恢复”同生共死。</li>
 * </ul>
 */
@Service
public class ReversalService {

    private final ClearingBatchRepository batchRepo;
    private final ReversalRequestRepository requestRepo;
    private final ReversalDecisionRepository decisionRepo;
    private final ReceivableRepository receivableRepo;
    private final NettingAgreementRepository agreementRepo;
    private final AdjustmentRequestRepository adjustmentRequestRepo;
    private final DayLockService dayLock;
    private final ClosingService closingService;

    public ReversalService(ClearingBatchRepository batchRepo,
                           ReversalRequestRepository requestRepo,
                           ReversalDecisionRepository decisionRepo,
                           ReceivableRepository receivableRepo,
                           NettingAgreementRepository agreementRepo,
                           AdjustmentRequestRepository adjustmentRequestRepo,
                           DayLockService dayLock,
                           ClosingService closingService) {
        this.batchRepo = batchRepo;
        this.requestRepo = requestRepo;
        this.decisionRepo = decisionRepo;
        this.receivableRepo = receivableRepo;
        this.agreementRepo = agreementRepo;
        this.adjustmentRequestRepo = adjustmentRequestRepo;
        this.dayLock = dayLock;
        this.closingService = closingService;
    }

    private boolean adjustmentRequestExists(String batchId) {
        // 仅“进行中或已生效”的更正与撤销互斥；已驳回的更正不阻止重新撤销。
        return adjustmentRequestRepo.findByOriginalBatchId(batchId)
                .filter(r -> r.getStatus() == com.treasury.clearing.domain.AdjustmentStatus.REQUESTED
                        || r.getStatus() == com.treasury.clearing.domain.AdjustmentStatus.PARTIALLY_APPROVED
                        || r.getStatus() == com.treasury.clearing.domain.AdjustmentStatus.PROCESSED)
                .isPresent();
    }

    /** 发起撤销申请：仅 CONFIRMED 的普通批次；按协议门槛快照一审/双审名额。 */
    @Transactional
    public ReversalRequest requestReversal(String batchId, String reason, String by) {
        Instant now = Instant.now();
        ClearingBatch batch = batchRepo.findByIdForUpdate(batchId)
                .orElseThrow(() -> new NoSuchElementException("批次不存在: " + batchId));

        if (batch.getKind() == BatchKind.REVERSAL || batch.getKind() == BatchKind.ADJUSTMENT) {
            throw new ConflictException(batch.getKind() + " 批次不可再撤销: " + batchId);
        }
        switch (batch.getStatus()) {
            case REVERSAL_PENDING -> throw new ConflictException("该批次已有进行中的撤销申请，请勿重复提交: " + batchId);
            case ADJUSTMENT_PENDING -> throw new ConflictException("该批次差额更正进行中，撤销与更正互斥: " + batchId);
            case REVERSED -> throw new ConflictException("该批次已冲正（终态），不能再次撤销: " + batchId);
            case SIMULATED -> throw new ConflictException("试算批次无需撤销，可直接重新试算: " + batchId);
            case CONFIRMED -> { /* 允许 */ }
        }
        if (batch.getAdjustmentBatchId() != null) {
            throw new ConflictException("该批次已完成差额更正，不能整单撤销（更正与撤销互斥）: " + batchId);
        }
        if (adjustmentRequestExists(batchId)) {
            throw new ConflictException("该批次存在差额更正申请，撤销与更正互斥: " + batchId);
        }
        if (requestRepo.findActiveByBatch(batchId).isPresent()) {
            throw new ConflictException("该批次已存在进行中的撤销申请，请勿重复提交: " + batchId);
        }

        ThresholdSnapshot threshold = resolveThreshold(batch);

        batch.markReversalPending(now);
        ReversalRequest request = new ReversalRequest(
                "RR-" + UUID.randomUUID().toString().substring(0, 8),
                batch, reason, by != null ? by : "资金专员", now,
                threshold.requiredApprovals(), threshold.agreementCode(),
                threshold.thresholdAmount(), threshold.grossCleared(),
                threshold.currency());
        try {
            requestRepo.saveAndFlush(request);
            batchRepo.save(batch);
            return request;
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("该批次的撤销申请已存在（并发重复提交被拒绝）: " + batchId);
        }
    }

    /**
     * 提交一条审批决议（APPROVE / REJECT）。
     * 返回本次决策后的申请；若凑满名额，返回时冲正批次已在同事务生成、债权已恢复。
     */
    @Transactional
    public ReversalRequest decide(String batchId, String approverRaw, String comment,
                                  ReversalDecision.Outcome outcome) {
        Instant now = Instant.now();
        String approver = approverRaw != null && !approverRaw.isBlank() ? approverRaw.trim() : null;
        if (approver == null) {
            throw new IllegalArgumentException("审批人不能为空");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("审批结果（APPROVE/REJECT）不能为空");
        }

        // 先锁原批次行串行化；再找该批次的撤销申请（可能有多条历史，终态决策必须返回 409）。
        ClearingBatch origin = batchRepo.findByIdForUpdate(batchId)
                .orElseThrow(() -> new NoSuchElementException("原批次不存在: " + batchId));
        List<ReversalRequest> all = requestRepo.findByOriginalBatchIdOrderByRequestedAtDescForUpdate(batchId);
        if (all.isEmpty()) {
            throw new NoSuchElementException("批次 " + batchId + " 没有撤销申请");
        }
        ReversalRequest request = all.get(0); // 最新一条（活动或终态）
        if (all.stream().anyMatch(r -> r.getStatus() == ReversalStatus.REQUESTED
                || r.getStatus() == ReversalStatus.PARTIALLY_APPROVED)) {
            request = all.stream().filter(r -> r.getStatus() == ReversalStatus.REQUESTED
                    || r.getStatus() == ReversalStatus.PARTIALLY_APPROVED).findFirst().orElse(request);
        }

        // 终态短路：已冲正/已驳回后任何再决策都 409，绝不新增决议或改动债权。
        if (request.getStatus() == ReversalStatus.PROCESSED) {
            throw new ConflictException("撤销已处理完成，冲正批次为 "
                    + request.getReversalBatchId() + "，请勿重复审批");
        }
        if (request.getStatus() == ReversalStatus.REJECTED) {
            throw new ConflictException("该撤销申请已被驳回（终态），不能再提交审批决议");
        }
        if (origin.getStatus() != BatchStatus.REVERSAL_PENDING) {
            throw new ConflictException("原批次状态为 " + origin.getStatus()
                    + "，不处于待审批撤销，已拒绝本次决议");
        }

        // 四眼：申请人不得参与审批。
        if (approver.equals(request.getRequestedBy())) {
            throw new ConflictException("申请人不能审批自己的撤销申请（四眼原则）：" + approver);
        }

        // 锁既有决议并去重：同一审批人对同一申请最多一条决议。
        List<ReversalDecision> decisions = decisionRepo.listByRequestForUpdate(request.getId());
        for (ReversalDecision d : decisions) {
            if (d.getApprover().equals(approver)) {
                throw new ConflictException("审批人 " + approver
                        + " 已对该撤销申请提交过决议（" + d.getOutcome()
                        + "），不能重复提交，也不重复占用审批名额");
            }
        }
        long approvals = decisions.stream()
                .filter(d -> d.getOutcome() == ReversalDecision.Outcome.APPROVE).count();
        int seq = decisions.size() + 1;
        ReversalStatus statusBefore = request.getStatus();

        if (outcome == ReversalDecision.Outcome.REJECT) {
            if (approvals >= request.getRequiredApprovals()) {
                // 理论不可达（名额已满应已 PROCESSED）
                throw new ConflictException("审批名额已满，不能驳回");
            }
            request.reject(approver, now, comment);
            origin.markReversalRejected();
            ReversalDecision decision = new ReversalDecision(
                    "RD-" + UUID.randomUUID().toString().substring(8), request, seq,
                    ReversalDecision.Outcome.REJECT, approver, comment, now,
                    statusBefore, ReversalStatus.REJECTED, null);
            persistDecisionAndRequest(decision, request, origin);
            return request;
        }

        // APPROVE：名额已满（极端并发被锁挡住，此处为防御）
        if (approvals >= request.getRequiredApprovals()
                || request.getApprovalsReceived() >= request.getRequiredApprovals()) {
            throw new ConflictException("审批名额已满，冲正批次为 "
                    + request.getReversalBatchId() + "，多余审批被拒绝");
        }

        boolean willFinalize =
                request.getApprovalsReceived() + 1 >= request.getRequiredApprovals();

        if (!willFinalize) {
            // 中途通过：仅推进到 PARTIALLY_APPROVED，决议独立提交；不生成冲正、不动债权。
            ReversalStatus statusAfter = ReversalStatus.PARTIALLY_APPROVED;
            request.recordApproval();
            ReversalDecision decision = new ReversalDecision(
                    "RD-" + UUID.randomUUID().toString().substring(8), request, seq,
                    ReversalDecision.Outcome.APPROVE, approver, comment, now,
                    statusBefore, statusAfter, null);
            persistDecisionAndRequest(decision, request, origin);
            return request;
        }

        // 末审将生效（冲正批次当日 confirmedAt=now）：在改债权/批次前取结算日统一锁，
        // 与关账串行，杜绝“关账漏掉冲正批次”或“关账后冲正生效”。
        dayLock.acquire(ClosingService.dateOf(now));
        // 日锁只串行化并发；已提交的关账仍需状态判定（锁在对方提交后已释放）。
        if (closingService.isDateClosed(now)) {
            throw new ConflictException("结算日 " + ClosingService.dateOf(now)
                    + " 已关账（或再开账审批中），禁止撤销冲正生效，请先完成再开账审批");
        }

        // 末审通过：决议 + 冲正批次 + 债权恢复 + 原批次终态，单事务原子完成。
        String reversalBatchId = "RVL-" + UUID.randomUUID().toString().substring(0, 8);
        ClearingBatch reversalBatch = buildReversalBatch(reversalBatchId, origin, now, approver);

        Set<String> clearedIds = new LinkedHashSet<>();
        for (ClearingGroup g : origin.getGroups()) {
            for (InvoiceDischarge d : g.getDischarges()) {
                clearedIds.add(d.getReceivableId());
            }
        }
        List<Receivable> toRestore = receivableRepo.findAllByIdForUpdate(clearedIds);
        if (toRestore.size() != clearedIds.size()) {
            throw new ConflictException("原批次清偿的部分债权已不存在，冲正中止: " + batchId);
        }
        int restored = 0;
        for (Receivable r : toRestore) {
            // 只允许 CLEARED→ACTIVE，任何非 CLEARED（重复恢复/并发改动）抛 409 并整体回滚
            r.reactivate();
            restored++;
        }
        receivableRepo.saveAll(toRestore);

        origin.markReversed(now, reversalBatchId);
        request.markProcessed(reversalBatchId, restored, approver, now);
        batchRepo.save(reversalBatch);
        batchRepo.save(origin);

        ReversalDecision decision = new ReversalDecision(
                "RD-" + UUID.randomUUID().toString().substring(8), request, seq,
                ReversalDecision.Outcome.APPROVE, approver, comment, now,
                statusBefore, ReversalStatus.PROCESSED, reversalBatchId);
        decisionRepo.save(decision);
        requestRepo.save(request);
        return request;
    }

    private void persistDecisionAndRequest(ReversalDecision decision, ReversalRequest request,
                                           ClearingBatch origin) {
        try {
            decisionRepo.saveAndFlush(decision);
        } catch (DataIntegrityViolationException dup) {
            // (request_id, approver) 唯一索引兜底并发重复
            throw new ConflictException("审批人 " + decision.getApprover()
                    + " 的决议已存在（并发重复提交被拒绝）");
        }
        requestRepo.save(request);
        batchRepo.save(origin);
    }

    @Transactional(readOnly = true)
    public ReversalRequest getRequestForBatch(String batchId) {
        return requestRepo.findByOriginalBatchId(batchId)
                .orElseThrow(() -> new NoSuchElementException(
                        "批次 " + batchId + " 没有撤销申请"));
    }

    /** 逐组比较“实际清偿额 vs 协议门槛”，取所有组需要的最高名额并快照触发门槛。 */
    private ThresholdSnapshot resolveThreshold(ClearingBatch batch) {
        int required = 1;
        String triggerAgreement = null;
        BigDecimal triggerThreshold = null;
        BigDecimal triggerGross = null;
        String triggerCurrency = null;

        List<ClearingGroup> groups = new ArrayList<>(batch.getGroups());
        for (ClearingGroup g : groups) {
            BigDecimal gross = g.getDischarges().stream()
                    .map(InvoiceDischarge::getConvertedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            NettingAgreement agreement = agreementRepo.findById(g.getAgreementCode()).orElse(null);
            BigDecimal threshold = agreement != null ? agreement.getDualApprovalThreshold() : null;
            if (threshold != null && gross.compareTo(threshold) >= 0) {
                required = 2;
                // 记录触发双审的最高门槛组（按清偿额取较大者）
                if (triggerGross == null || gross.compareTo(triggerGross) > 0) {
                    triggerAgreement = g.getAgreementCode();
                    triggerThreshold = threshold;
                    triggerGross = gross;
                    triggerCurrency = g.getClearingCurrency();
                }
            }
        }
        return new ThresholdSnapshot(required, triggerAgreement, triggerThreshold,
                triggerGross, triggerCurrency);
    }

    /** 镜像构建冲正批次：净头寸/指令取反，方向交换；金额与发票清偿原样留痕。 */
    private ClearingBatch buildReversalBatch(String id, ClearingBatch origin,
                                             Instant now, String by) {
        ClearingBatch rev = ClearingBatch.reversal(id,
                "冲正批次 ← " + origin.getLabel(), now, origin.getValuationTime(),
                origin.getOriginalClaimCount(), origin.getResultingEntryCount(),
                origin.getExcludedCount(), by, origin.getId());

        List<ClearingGroup> originGroups = new ArrayList<>(origin.getGroups());
        originGroups.sort((a, b) -> a.getId().compareTo(b.getId()));
        int seq = 0;
        for (ClearingGroup og : originGroups) {
            ClearingGroup ng = new ClearingGroup(
                    id + "-G" + (++seq), rev, og.getAgreementCode(),
                    og.getClearingCurrency(), og.isCrossCurrency(), og.getClaimCount(),
                    og.getGrossClaimsDisplay(), og.getNetEntryCount());
            rev.addGroup(ng);

            int pSeq = 0;
            for (NetPosition p : og.getPositions()) {
                ng.addPosition(new NetPosition(ng.getId() + "-P" + (++pSeq), ng,
                        p.getEntityCode(), p.getGrossPayable(), p.getGrossReceivable(),
                        p.getNetAmount().negate()));
            }

            int eSeq = 0;
            for (ClearingEntry e : og.getEntries()) {
                PaymentType type = e.getType();
                ng.addEntry(new ClearingEntry(ng.getId() + "-E" + (++eSeq), ng, type,
                        e.getToEntity(), e.getFromEntity(), e.getAmount(), e.getCurrency(),
                        "[冲正] " + e.getDescription()));
            }

            int dSeq = 0;
            for (InvoiceDischarge d : og.getDischarges()) {
                ng.addDischarge(new InvoiceDischarge(ng.getId() + "-D" + (++dSeq), ng,
                        d.getReceivableId(), d.getInvoiceNo(),
                        d.getDebtorCode(), d.getCreditorCode(),
                        d.getOriginalCurrency(), d.getOriginalAmount(),
                        d.getConvertedAmount(),
                        d.getSetoffAmount(), d.getPaymentAmount(), d.getFxRate()));
            }

            int rSeq = 0;
            for (RoundingLine r : og.getRoundingLines()) {
                BigDecimal neg = r.getAmount().negate();
                if ("BEARER_ADJUST".equals(r.getLineType())) {
                    ng.addRoundingLine(RoundingLine.bearer(ng.getId() + "-R" + (++rSeq), ng,
                            r.getEntityCode(), r.getCurrency(), neg, "[冲正] " + r.getNote()));
                } else {
                    ng.addRoundingLine(RoundingLine.fx(ng.getId() + "-R" + (++rSeq), ng,
                            r.getEntityCode(), r.getCurrency(), neg,
                            r.getRefInvoiceNo(), r.getFxRate(), r.getRateTime(),
                            "[冲正] " + r.getNote()));
                }
            }
        }
        return rev;
    }

    private record ThresholdSnapshot(int requiredApprovals, String agreementCode,
                                     BigDecimal thresholdAmount, BigDecimal grossCleared,
                                     String currency) {
    }
}
