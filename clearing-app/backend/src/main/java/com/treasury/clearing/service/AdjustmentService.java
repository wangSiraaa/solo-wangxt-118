package com.treasury.clearing.service;

import com.treasury.clearing.calc.AdjustmentEngine;
import com.treasury.clearing.calc.AdjustmentGroupResult;
import com.treasury.clearing.calc.AdjustmentInstruction;
import com.treasury.clearing.calc.CorrectionInput;
import com.treasury.clearing.calc.Currencies;
import com.treasury.clearing.domain.AdjustmentDecision;
import com.treasury.clearing.domain.AdjustmentRequest;
import com.treasury.clearing.domain.AdjustmentStatus;
import com.treasury.clearing.domain.BatchKind;
import com.treasury.clearing.domain.BatchStatus;
import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.ClearingEntry;
import com.treasury.clearing.domain.ClearingGroup;
import com.treasury.clearing.domain.ConflictException;
import com.treasury.clearing.domain.FxRate;
import com.treasury.clearing.domain.InvoiceCorrectionEvent;
import com.treasury.clearing.domain.NetPosition;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.domain.PaymentType;
import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.repo.AdjustmentDecisionRepository;
import com.treasury.clearing.repo.AdjustmentRequestRepository;
import com.treasury.clearing.repo.ClearingBatchRepository;
import com.treasury.clearing.repo.FxRateRepository;
import com.treasury.clearing.repo.InvoiceCorrectionEventRepository;
import com.treasury.clearing.repo.NettingAgreementRepository;
import com.treasury.clearing.repo.ReceivableRepository;
import com.treasury.clearing.repo.ReversalRequestRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * 确认方案差额更正（分级四眼审批）。
 *
 * <p>与撤销互斥、不改写原批次、不清偿/恢复任何原始债权：原始发票金额与状态永久保留，
 * 更正仅以不可修改事件 + 唯一 {@code ADJUSTMENT} 差额批次表达净额差异。
 *
 * <p>门槛按“本次更正实际差额绝对值”与协议 {@code dual_approval_threshold} 比较决定一审/双审；
 * 申请人不得审批；决议只增不改；名额凑满的末审在同事务幂等生成差额批次。
 */
@Service
public class AdjustmentService {

    private final ClearingBatchRepository batchRepo;
    private final ReceivableRepository receivableRepo;
    private final NettingAgreementRepository agreementRepo;
    private final FxRateRepository fxRepo;
    private final AdjustmentRequestRepository requestRepo;
    private final AdjustmentDecisionRepository decisionRepo;
    private final InvoiceCorrectionEventRepository eventRepo;
    private final ReversalRequestRepository reversalRepo;
    private final AdjustmentEngine engine;
    private final org.springframework.beans.factory.ObjectProvider<ClosingService> closingServiceProvider;

    public AdjustmentService(ClearingBatchRepository batchRepo,
                             ReceivableRepository receivableRepo,
                             NettingAgreementRepository agreementRepo,
                             FxRateRepository fxRepo,
                             AdjustmentRequestRepository requestRepo,
                             AdjustmentDecisionRepository decisionRepo,
                             InvoiceCorrectionEventRepository eventRepo,
                             ReversalRequestRepository reversalRepo,
                             AdjustmentEngine engine,
                             org.springframework.beans.factory.ObjectProvider<ClosingService> closingServiceProvider) {
        this.batchRepo = batchRepo;
        this.receivableRepo = receivableRepo;
        this.agreementRepo = agreementRepo;
        this.fxRepo = fxRepo;
        this.requestRepo = requestRepo;
        this.decisionRepo = decisionRepo;
        this.eventRepo = eventRepo;
        this.reversalRepo = reversalRepo;
        this.engine = engine;
        this.closingServiceProvider = closingServiceProvider;
    }

    /** 单张发票更正请求。 */
    public record CorrectionSpec(String receivableId,
                                 BigDecimal newAmount,
                                 String newAgreementCode,
                                 String reason,
                                 String effectiveScope) {
    }

    /** 发起更正申请（可一次携带多张发票；同一发票在同一批次只能更正一次）。 */
    @Transactional
    public AdjustmentRequest request(String originalBatchId, List<CorrectionSpec> specs,
                                     String reason, String by) {
        Instant now = Instant.now();
        ClearingBatch origin = batchRepo.findByIdForUpdate(originalBatchId)
                .orElseThrow(() -> new NoSuchElementException("批次不存在: " + originalBatchId));

        if (origin.getKind() != BatchKind.NETTING) {
            throw new ConflictException("只有普通清算批次可发起差额更正: " + originalBatchId);
        }
        switch (origin.getStatus()) {
            case REVERSAL_PENDING -> throw new ConflictException("撤销流程进行中，撤销与更正互斥: " + originalBatchId);
            case ADJUSTMENT_PENDING -> throw new ConflictException("该批次已有进行中的更正申请: " + originalBatchId);
            case REVERSED -> throw new ConflictException("批次已冲正，不能更正: " + originalBatchId);
            case SIMULATED -> throw new ConflictException("试算批次请直接重新试算，无需差额更正");
            case CONFIRMED -> { /* 允许 */ }
        }
        if (origin.getAdjustmentBatchId() != null || requestRepo.existsByOriginalBatchId(originalBatchId)) {
            throw new ConflictException("该批次已发起过差额更正，不能重复更正");
        }
        // 仅“进行中”的撤销与更正互斥；已驳回撤销不阻止发起更正。
        boolean activeReversal = reversalRepo.findByOriginalBatchId(originalBatchId)
                .filter(r -> r.getStatus() == com.treasury.clearing.domain.ReversalStatus.REQUESTED
                        || r.getStatus() == com.treasury.clearing.domain.ReversalStatus.PARTIALLY_APPROVED)
                .isPresent();
        if (activeReversal) {
            throw new ConflictException("该批次撤销申请进行中，撤销与更正互斥");
        }
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("至少包含一张发票更正");
        }

        // 原始已清偿发票索引（只允许更正本批次实际清偿过的发票）
        Map<String, InvoiceDischargeRef> discharged = new HashMap<>();
        for (ClearingGroup g : origin.getGroups()) {
            g.getDischarges().forEach(d -> discharged.put(d.getReceivableId(),
                    new InvoiceDischargeRef(g.getAgreementCode(), g.getClearingCurrency(),
                            d.getConvertedAmount(), d.getFxRate(),
                            d.getCreditorCode(), d.getDebtorCode())));
        }

        // 事件预计算（按原估值时点折算），并去重同一发票
        Set<String> seen = new HashSet<>();
        List<PendingEvent> pending = new ArrayList<>();
        for (CorrectionSpec spec : specs) {
            if (!seen.add(spec.receivableId())) {
                throw new ConflictException("同一发票在一次更正中出现多次: " + spec.receivableId());
            }
            Receivable r = receivableRepo.findById(spec.receivableId())
                    .orElseThrow(() -> new NoSuchElementException("发票不存在: " + spec.receivableId()));
            InvoiceDischargeRef ref = discharged.get(spec.receivableId());
            if (ref == null) {
                throw new ConflictException("发票 " + r.getInvoiceNo()
                        + " 不在原确认批次的清偿明细中，不能对其差额更正");
            }
            BigDecimal newAmount = spec.newAmount() != null ? spec.newAmount() : r.getAmount();
            if (newAmount.signum() <= 0) {
                throw new IllegalArgumentException("更正后金额必须为正: " + r.getInvoiceNo());
            }
            String newAgreement = spec.newAgreementCode() != null && !spec.newAgreementCode().isBlank()
                    ? spec.newAgreementCode() : ref.agreementCode();
            NettingAgreement target = agreementRepo.findById(newAgreement)
                    .orElseThrow(() -> new ConflictException("更正后协议不存在: " + newAgreement));
            boolean agreementChanged = !newAgreement.equals(ref.agreementCode());
            String targetCcy = target.isCrossCurrency() ? target.getSettlementCurrency() : r.getCurrency();
            if (!targetCcy.equals(ref.clearingCurrency())) {
                throw new ConflictException("发票 " + r.getInvoiceNo()
                        + " 更正后协议的清算币种为 " + targetCcy + "，与原批次清算币种 "
                        + ref.clearingCurrency() + " 不一致；差额更正仅支持同清算币种内更正");
            }
            String effAgreement = newAgreement;
            String clearingCcy = ref.clearingCurrency();

            BigDecimal rate = BigDecimal.ONE;
            if (!r.getCurrency().equals(clearingCcy)) {
                FxRate fx = latestFx(r.getCurrency(), clearingCcy, origin.getValuationTime());
                rate = fx.getRate();
            }
            BigDecimal oldConverted = ref.convertedAmount();
            BigDecimal newConverted = r.getCurrency().equals(clearingCcy)
                    ? Currencies.roundCash(r.getCurrency(), newAmount)
                    : Currencies.convert(newAmount, rate, clearingCcy);

            pending.add(new PendingEvent(r, ref, spec, agreementChanged, effAgreement, clearingCcy,
                    oldConverted, newConverted, rate));
        }

        // 按受影响（协议, 清算币种）分组，计算差额，决定门槛
        Map<GroupKey, List<PendingEvent>> byGroup = new LinkedHashMap<>();
        for (PendingEvent e : pending) {
            byGroup.computeIfAbsent(new GroupKey(e.effAgreement(), e.clearingCcy()),
                    k -> new ArrayList<>()).add(e);
        }
        int required = 1;
        String triggerAgreement = null;
        BigDecimal triggerThreshold = null;
        BigDecimal maxAbsDelta = BigDecimal.ZERO;
        String triggerCcy = null;
        for (Map.Entry<GroupKey, List<PendingEvent>> e : byGroup.entrySet()) {
            BigDecimal absDelta = e.getValue().stream()
                    .map(x -> x.newConverted().subtract(x.oldConverted()).abs())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            NettingAgreement a = agreementRepo.findById(e.getKey().agreementCode()).orElseThrow();
            BigDecimal threshold = a.getDualApprovalThreshold();
            if (threshold != null && absDelta.compareTo(threshold) >= 0) {
                required = 2;
                if (absDelta.compareTo(maxAbsDelta) > 0) {
                    maxAbsDelta = absDelta;
                    triggerAgreement = a.getCode();
                    triggerThreshold = threshold;
                    triggerCcy = e.getKey().clearingCurrency();
                }
            }
        }

        origin.markAdjustmentPending(now);
        AdjustmentRequest request = new AdjustmentRequest(
                "AR-" + UUID.randomUUID().toString().substring(0, 8), origin,
                reason, by != null ? by : "资金专员", now, required,
                triggerAgreement, triggerThreshold, maxAbsDelta, triggerCcy);
        try {
            requestRepo.saveAndFlush(request);
            batchRepo.save(origin);
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("该批次的更正申请已存在（并发重复提交被拒绝）");
        }

        // 落不可修改更正事件（申请已 saveAndFlush，外键可解析）
        int seq = 0;
        for (PendingEvent e : pending) {
            InvoiceCorrectionEvent.Field field = e.agreementChanged()
                    ? InvoiceCorrectionEvent.Field.AGREEMENT
                    : InvoiceCorrectionEvent.Field.AMOUNT;
            eventRepo.save(new InvoiceCorrectionEvent(
                    "CE-" + UUID.randomUUID().toString().substring(0, 8), request, ++seq,
                    e.receivable().getId(), e.receivable().getInvoiceNo(), field,
                    e.receivable().getAmount(), e.receivable().getCurrency(), e.ref().agreementCode(),
                    e.spec().newAmount() != null ? e.spec().newAmount() : e.receivable().getAmount(),
                    e.receivable().getCurrency(), e.effAgreement(),
                    e.spec().effectiveScope(),
                    e.spec().reason() != null ? e.spec().reason() : reason,
                    by, now,
                    e.oldConverted(), e.newConverted(), e.clearingCcy()));
        }
        request.setEventCount(pending.size());
        requestRepo.saveAndFlush(request);
        eventRepo.flush();
        return request;
    }

    /** 提交一条四眼决议；末审凑满名额时在同事务生成唯一差额批次。 */
    @Transactional
    public AdjustmentRequest decide(String batchId, String approverRaw, String comment,
                                   AdjustmentDecision.Outcome outcome) {
        Instant now = Instant.now();
        String approver = approverRaw != null && !approverRaw.isBlank() ? approverRaw.trim() : null;
        if (approver == null) {
            throw new IllegalArgumentException("审批人不能为空");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("审批结果不能为空");
        }

        AdjustmentRequest request = requestRepo.findByOriginalBatchIdForUpdate(batchId)
                .orElseThrow(() -> new NoSuchElementException("批次 " + batchId + " 没有更正申请"));
        ClearingBatch origin = batchRepo.findByIdForUpdate(batchId)
                .orElseThrow(() -> new NoSuchElementException("原批次不存在: " + batchId));

        if (request.getStatus() == AdjustmentStatus.PROCESSED) {
            throw new ConflictException("差额更正已处理完成，差额批次为 "
                    + request.getAdjustmentBatchId() + "，请勿重复审批");
        }
        if (request.getStatus() == AdjustmentStatus.REJECTED) {
            throw new ConflictException("差额更正已被驳回（终态），不能再提交决议");
        }
        if (origin.getStatus() != BatchStatus.ADJUSTMENT_PENDING) {
            throw new ConflictException("原批次状态为 " + origin.getStatus() + "，不处于更正审批中");
        }
        if (approver.equals(request.getRequestedBy())) {
            throw new ConflictException("申请人不能审批自己的更正申请（四眼原则）：" + approver);
        }

        List<AdjustmentDecision> decisions = decisionRepo.listByRequestForUpdate(request.getId());
        for (AdjustmentDecision d : decisions) {
            if (d.getApprover().equals(approver)) {
                throw new ConflictException("审批人 " + approver + " 已提交过更正决议，不能重复提交");
            }
        }
        long approvals = decisions.stream()
                .filter(d -> d.getOutcome() == AdjustmentDecision.Outcome.APPROVE).count();
        int seq = decisions.size() + 1;
        AdjustmentStatus before = request.getStatus();

        if (outcome == AdjustmentDecision.Outcome.REJECT) {
            request.reject(approver, now, comment);
            origin.markAdjustmentRejected();
            saveDecision(new AdjustmentDecision("AD-" + UUID.randomUUID().toString().substring(8),
                    request, seq, AdjustmentDecision.Outcome.REJECT, approver, comment, now,
                    before, AdjustmentStatus.REJECTED, null));
            requestRepo.save(request);
            batchRepo.save(origin);
            return request;
        }

        if (approvals >= request.getRequiredApprovals()
                || request.getApprovalsReceived() >= request.getRequiredApprovals()) {
            throw new ConflictException("审批名额已满，差额批次为 " + request.getAdjustmentBatchId());
        }
        boolean willFinalize =
                request.getApprovalsReceived() + 1 >= request.getRequiredApprovals();

        if (!willFinalize) {
            request.recordApproval();
            saveDecision(new AdjustmentDecision("AD-" + UUID.randomUUID().toString().substring(8),
                    request, seq, AdjustmentDecision.Outcome.APPROVE, approver, comment, now,
                    before, AdjustmentStatus.PARTIALLY_APPROVED, null));
            requestRepo.save(request);
            return request;
        }

        // 末审：基于已落库的不可修改事件重算差额，幂等生成差额批次
        List<InvoiceCorrectionEvent> events = eventRepo.listByRequest(request.getId());
        Map<GroupKey, List<InvoiceCorrectionEvent>> byGroup = new LinkedHashMap<>();
        for (InvoiceCorrectionEvent ev : events) {
            String ccy = ev.getClearingCurrency();
            String agreement = ev.getNewAgreementCode() != null ? ev.getNewAgreementCode()
                    : ev.getOldAgreementCode();
            byGroup.computeIfAbsent(new GroupKey(agreement, ccy), k -> new ArrayList<>()).add(ev);
        }

        // 末审：基于已落库的不可修改事件重算差额，幂等生成差额批次。
        // 已关账日期禁止差额更正生效（差额批次当日 confirmedAt=now）。
        closingServiceProvider.getObject().assertDateNotClosed(now, "差额更正生效");

        String adjustmentBatchId = "ADJ-" + UUID.randomUUID().toString().substring(0, 8);
        ClearingBatch adjBatch = ClearingBatch.adjustment(adjustmentBatchId,
                "差额更正批次 ← " + origin.getLabel(), now, origin.getValuationTime(),
                events.size(), 0, approver, origin.getId());

        int totalEntries = 0;
        int gSeq = 0;
        for (Map.Entry<GroupKey, List<InvoiceCorrectionEvent>> g : byGroup.entrySet()) {
            List<CorrectionInput> inputs = g.getValue().stream()
                    .map(ev -> new CorrectionInput(ev.getReceivableId(), ev.getInvoiceNo(),
                            creditorOf(origin, ev.getReceivableId()),
                            debtorOf(origin, ev.getReceivableId()),
                            g.getKey().agreementCode(), ev.getClearingCurrency(),
                            ev.getOldCurrency(), ev.getOldAmount(), ev.getNewAmount(),
                            ev.getOldConverted(), ev.getNewConverted(), ev.getDeltaConverted()))
                    .sorted(Comparator.comparing(CorrectionInput::receivableId))
                    .toList();            AdjustmentGroupResult result = engine.compute(
                    g.getKey().agreementCode(), g.getKey().clearingCurrency(), inputs);

            ClearingGroup cg = new ClearingGroup(
                    adjustmentBatchId + "-G" + (++gSeq), adjBatch,
                    g.getKey().agreementCode(), g.getKey().clearingCurrency(),
                    isCross(g.getKey().agreementCode()), inputs.size(),
                    g.getKey().agreementCode() + " 差额组", result.instructions().size());
            adjBatch.addGroup(cg);

            int pSeq = 0;
            for (Map.Entry<String, BigDecimal> p : result.deltaNet().entrySet()) {
                cg.addPosition(new NetPosition(cg.getId() + "-P" + (++pSeq), cg,
                        p.getKey(), BigDecimal.ZERO, BigDecimal.ZERO, p.getValue()));
            }
            int eSeq = 0;
            for (AdjustmentInstruction ins : result.instructions()) {
                cg.addEntry(new ClearingEntry(cg.getId() + "-E" + (++eSeq), cg,
                        PaymentType.ADJUSTMENT, ins.fromEntity(), ins.toEntity(),
                        ins.amount(), ins.currency(), ins.description()));
                totalEntries++;
            }
        }

        // 原批次保持 CONFIRMED（不冲正），仅登记双向关联；债权状态/金额一律不动
        origin.markAdjusted(adjustmentBatchId);
        request.markProcessed(adjustmentBatchId, events.size(), approver, now);
        batchRepo.save(adjBatch);
        batchRepo.save(origin);

        saveDecision(new AdjustmentDecision("AD-" + UUID.randomUUID().toString().substring(8),
                request, seq, AdjustmentDecision.Outcome.APPROVE, approver, comment, now,
                before, AdjustmentStatus.PROCESSED, adjustmentBatchId));
        requestRepo.save(request);
        // totalEntries 仅用于日志语义，ADJUSTMENT 批次 resultingEntryCount 已由组指令数体现
        return request;
    }

    private boolean isCross(String agreementCode) {
        return agreementRepo.findById(agreementCode).map(NettingAgreement::isCrossCurrency).orElse(false);
    }

    private String creditorOf(ClearingBatch origin, String receivableId) {
        return origin.getGroups().stream()
                .flatMap(g -> g.getDischarges().stream())
                .filter(d -> d.getReceivableId().equals(receivableId)).findFirst()
                .map(com.treasury.clearing.domain.InvoiceDischarge::getCreditorCode)
                .orElseThrow(() -> new NoSuchElementException("清偿明细缺失: " + receivableId));
    }

    private String debtorOf(ClearingBatch origin, String receivableId) {
        return origin.getGroups().stream()
                .flatMap(g -> g.getDischarges().stream())
                .filter(d -> d.getReceivableId().equals(receivableId)).findFirst()
                .map(com.treasury.clearing.domain.InvoiceDischarge::getDebtorCode)
                .orElseThrow(() -> new NoSuchElementException("清偿明细缺失: " + receivableId));
    }

    private void saveDecision(AdjustmentDecision d) {
        try {
            decisionRepo.saveAndFlush(d);
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("审批人 " + d.getApprover() + " 的更正决议已存在（并发重复被拒绝）");
        }
    }

    private FxRate latestFx(String from, String to, Instant asOf) {
        return fxRepo.findByFromCurrencyAndToCurrencyOrderByRateTimeDesc(from, to).stream()
                .filter(fx -> !fx.getRateTime().isAfter(asOf))
                .findFirst()
                .orElseThrow(() -> new ClearingRuleException(
                        "缺少 " + asOf + " 时点可用的汇率 " + from + "->" + to));
    }

    @Transactional(readOnly = true)
    public AdjustmentRequest getForBatch(String batchId) {
        return requestRepo.findByOriginalBatchId(batchId)
                .orElseThrow(() -> new NoSuchElementException("批次 " + batchId + " 没有更正申请"));
    }

    private record InvoiceDischargeRef(String agreementCode, String clearingCurrency,
                                       BigDecimal convertedAmount, BigDecimal fxRate,
                                       String creditor, String debtor) {
    }

    private record PendingEvent(Receivable receivable, InvoiceDischargeRef ref, CorrectionSpec spec,
                                boolean agreementChanged, String effAgreement,
                                String clearingCcy, BigDecimal oldConverted,
                                BigDecimal newConverted, BigDecimal rate) {
    }

    private record GroupKey(String agreementCode, String clearingCurrency) {
    }
}
