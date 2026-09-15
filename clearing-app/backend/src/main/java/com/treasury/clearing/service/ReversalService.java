package com.treasury.clearing.service;

import com.treasury.clearing.domain.BatchKind;
import com.treasury.clearing.domain.ConflictException;
import com.treasury.clearing.domain.BatchStatus;
import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.ClearingEntry;
import com.treasury.clearing.domain.ClearingGroup;
import com.treasury.clearing.domain.InvoiceDischarge;
import com.treasury.clearing.domain.NetPosition;
import com.treasury.clearing.domain.PaymentType;
import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.domain.ReversalRequest;
import com.treasury.clearing.domain.ReversalStatus;
import com.treasury.clearing.domain.RoundingLine;
import com.treasury.clearing.repo.ClearingBatchRepository;
import com.treasury.clearing.repo.ReceivableRepository;
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
 * 确认方案撤销与冲正。
 *
 * <p>状态机：{@code CONFIRMED → REVERSAL_PENDING → REVERSED}（驳回则回到 CONFIRMED）。
 * 原确认批次的金额、估值时点、指令、发票清偿、操作者永不修改，冲正通过**新增**一条
 * {@link BatchKind#REVERSAL} 批次表达，两者双向关联，形成不可逆审计链。
 *
 * <p>并发与幂等保证：
 * <ul>
 *   <li>批次/申请/债权均有乐观锁；关键路径对批次行、申请行、相关债权行加排他锁；</li>
 *   <li>{@code reversal_request.original_batch_id} 唯一约束兜底重复申请；</li>
 *   <li>冲正批次 id 在申请处理成功提交后才回填；处理中途失败（含重启）整事务回滚，
 *       申请仍为 REQUESTED，可安全重试；债权只允许 CLEARED→ACTIVE，重复恢复直接 409；</li>
 *   <li>同一确认批次最多生成一条冲正批次、最多恢复一次债权。</li>
 * </ul>
 */
@Service
public class ReversalService {

    private final ClearingBatchRepository batchRepo;
    private final ReversalRequestRepository requestRepo;
    private final ReceivableRepository receivableRepo;

    public ReversalService(ClearingBatchRepository batchRepo,
                           ReversalRequestRepository requestRepo,
                           ReceivableRepository receivableRepo) {
        this.batchRepo = batchRepo;
        this.requestRepo = requestRepo;
        this.receivableRepo = receivableRepo;
    }

    /** 发起撤销申请：仅 CONFIRMED 的普通批次可申请。 */
    @Transactional
    public ReversalRequest requestReversal(String batchId, String reason, String by) {
        Instant now = Instant.now();
        ClearingBatch batch = batchRepo.findByIdForUpdate(batchId)
                .orElseThrow(() -> new NoSuchElementException("批次不存在: " + batchId));

        if (batch.getKind() == BatchKind.REVERSAL) {
            throw new ConflictException("冲正批次本身不可再撤销: " + batchId);
        }
        switch (batch.getStatus()) {
            case REVERSAL_PENDING -> throw new ConflictException("该批次已有进行中的撤销申请，请勿重复提交: " + batchId);
            case REVERSED -> throw new ConflictException("该批次已冲正（终态），不能再次撤销: " + batchId);
            case SIMULATED -> throw new ConflictException("试算批次无需撤销，可直接重新试算: " + batchId);
            case CONFIRMED -> { /* 允许 */ }
        }
        if (requestRepo.existsByOriginalBatchId(batchId)) {
            throw new ConflictException("该批次已存在撤销申请，请勿重复提交: " + batchId);
        }

        batch.markReversalPending(now);
        ReversalRequest request = new ReversalRequest(
                "RR-" + UUID.randomUUID().toString().substring(0, 8),
                batch, ReversalStatus.REQUESTED, reason,
                by != null ? by : "资金专员", now);
        try {
            // 先存申请（唯一约束兜底并发），再存批次（乐观锁版本推进）
            requestRepo.saveAndFlush(request);
            batchRepo.save(batch);
            return request;
        } catch (DataIntegrityViolationException dup) {
            // 唯一约束兜底：两个并发申请只有一个能落库
            throw new ConflictException("该批次的撤销申请已存在（并发重复提交被拒绝）: " + batchId);
        }
    }

    /**
     * 审批通过并执行冲正。整个处理在一个事务里：
     * 锁申请行 → 幂等检查 → 建冲正批次 → 加锁恢复债权 → 原批次置 REVERSED → 回填申请。
     * 任一步失败整体回滚，重试不会产生第二条冲正批次或重复恢复。
     */
    @Transactional
    public ClearingBatch approveReversal(String batchId, String by) {
        Instant now = Instant.now();

        ReversalRequest request = requestRepo.findByOriginalBatchIdForUpdate(batchId)
                .orElseThrow(() -> new NoSuchElementException(
                        "批次 " + batchId + " 没有撤销申请"));
        if (request.getStatus() == ReversalStatus.PROCESSED) {
            // 审批接口的重复/并发调用：已完成则明确冲突，并指回既有冲正批次，绝不重做
            throw new ConflictException("撤销已处理完成，冲正批次为 "
                    + request.getReversalBatchId() + "，请勿重复审批");
        }
        if (request.getStatus() == ReversalStatus.REJECTED) {
            throw new ConflictException("该撤销申请已驳回，不能再审批通过");
        }

        // 锁原批次行（与申请行锁共同串行化并发审批/撤销）
        ClearingBatch origin = batchRepo.findByIdForUpdate(batchId)
                .orElseThrow(() -> new NoSuchElementException("原批次不存在: " + batchId));
        if (origin.getStatus() != BatchStatus.REVERSAL_PENDING) {
            throw new ConflictException("原批次状态为 " + origin.getStatus()
                    + "，不是待审批撤销，已拒绝本次处理");
        }

        String approver = by != null ? by : "资金主管";
        request.approve(approver, now);

        // 1) 幂等构建冲正批次（沿用原估值时点，保证账务依据一致、可复现）
        String reversalBatchId = "RVL-" + UUID.randomUUID().toString().substring(0, 8);
        ClearingBatch reversalBatch = buildReversalBatch(reversalBatchId, origin, now, approver);

        // 2) 加排他锁恢复本次确认实际清偿的债权（去重，防止一张发票多组时重复处理）
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
            // reactivate 内部只允许 CLEARED→ACTIVE；任何非 CLEARED 都抛 409 并回滚
            r.reactivate();
            restored++;
        }
        receivableRepo.saveAll(toRestore);

        // 3) 原批次置终态并登记冲正批次；冲正批次落库；申请回填（去重依据）
        origin.markReversed(now, reversalBatchId);
        request.markProcessed(reversalBatchId, restored, now);
        batchRepo.save(reversalBatch);
        batchRepo.save(origin);
        requestRepo.save(request);
        return reversalBatch;
    }

    /** 审批驳回：申请 REJECTED，原批次回到 CONFIRMED，债权不动。 */
    @Transactional
    public ReversalRequest rejectReversal(String batchId, String by, String reason) {
        Instant now = Instant.now();
        ReversalRequest request = requestRepo.findByOriginalBatchIdForUpdate(batchId)
                .orElseThrow(() -> new NoSuchElementException(
                        "批次 " + batchId + " 没有撤销申请"));
        ClearingBatch origin = batchRepo.findByIdForUpdate(batchId)
                .orElseThrow(() -> new NoSuchElementException("原批次不存在: " + batchId));
        if (request.getStatus() != ReversalStatus.REQUESTED
                || origin.getStatus() != BatchStatus.REVERSAL_PENDING) {
            throw new ConflictException("撤销申请已处理或原批次状态为 " + origin.getStatus()
                    + "，不能驳回");
        }
        String approver = by != null ? by : "资金主管";
        request.reject(approver, now, reason);
        origin.markReversalRejected();
        requestRepo.save(request);
        batchRepo.save(origin);
        return request;
    }

    @Transactional(readOnly = true)
    public ReversalRequest getRequestForBatch(String batchId) {
        return requestRepo.findByOriginalBatchId(batchId)
                .orElseThrow(() -> new NoSuchElementException(
                        "批次 " + batchId + " 没有撤销申请"));
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

            // 净头寸取反、毛应收/毛应付交换
            int pSeq = 0;
            for (NetPosition p : og.getPositions()) {
                ng.addPosition(new NetPosition(ng.getId() + "-P" + (++pSeq), ng,
                        p.getEntityCode(),
                        p.getGrossPayable(),
                        p.getGrossReceivable(),
                        p.getNetAmount().negate()));
            }

            // 指令方向交换（冲回原资金效果）
            int eSeq = 0;
            for (ClearingEntry e : og.getEntries()) {
                PaymentType type = e.getType();
                ng.addEntry(new ClearingEntry(ng.getId() + "-E" + (++eSeq), ng, type,
                        e.getToEntity(), e.getFromEntity(), e.getAmount(), e.getCurrency(),
                        "[冲正] " + e.getDescription()));
            }

            // 发票清偿镜像：债权/债务方交换，表示恢复原债权债务关系；金额留痕
            int dSeq = 0;
            for (InvoiceDischarge d : og.getDischarges()) {
                ng.addDischarge(new InvoiceDischarge(ng.getId() + "-D" + (++dSeq), ng,
                        d.getReceivableId(), d.getInvoiceNo(),
                        d.getDebtorCode(), d.getCreditorCode(),
                        d.getOriginalCurrency(), d.getOriginalAmount(),
                        d.getConvertedAmount(),
                        d.getSetoffAmount(), d.getPaymentAmount(), d.getFxRate()));
            }

            // 尾差行镜像取反（逐笔与承担方归集仍零和）
            int rSeq = 0;
            for (RoundingLine r : og.getRoundingLines()) {
                BigDecimal neg = r.getAmount().negate();
                if ("BEARER_ADJUST".equals(r.getLineType())) {
                    ng.addRoundingLine(RoundingLine.bearer(ng.getId() + "-R" + (++rSeq), ng,
                            r.getEntityCode(), r.getCurrency(), neg,
                            "[冲正] " + r.getNote()));
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
}
