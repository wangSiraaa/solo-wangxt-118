package com.treasury.clearing.service;

import com.treasury.clearing.domain.BatchKind;
import com.treasury.clearing.domain.BatchStatus;
import com.treasury.clearing.domain.ClosingContribution;
import com.treasury.clearing.domain.ClosingReport;
import com.treasury.clearing.domain.ClosingReportLine;
import com.treasury.clearing.domain.ClosingStatus;
import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.ClearingEntry;
import com.treasury.clearing.domain.ClearingGroup;
import com.treasury.clearing.domain.ConflictException;
import com.treasury.clearing.domain.InvoiceDischarge;
import com.treasury.clearing.domain.NetPosition;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.domain.ReopenDecision;
import com.treasury.clearing.domain.ReopenRequest;
import com.treasury.clearing.domain.ReopenStatus;
import com.treasury.clearing.repo.ClosingBatchQueryRepository;
import com.treasury.clearing.repo.ClosingReportRepository;
import com.treasury.clearing.repo.NettingAgreementRepository;
import com.treasury.clearing.repo.ReopenDecisionRepository;
import com.treasury.clearing.repo.ReopenRequestRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 日终关账与合规再开账。
 *
 * <p>关账按结算日把当日生效的 NETTING / ADJUSTMENT / REVERSAL 批次聚合成不可修改报表快照
 * （汇总行 + 批次贡献链）。同一日同一时刻只允许一个有效关账；关账后该日禁止确认/撤销生效/更正生效。
 * 再开账走四眼审批，通过后旧快照置 SUPERSEDED、生成版本 +1 的新快照，历史永久保留。
 */
@Service
public class ClosingService {

    private static final Set<BatchStatus> EFFECTIVE_STATUSES =
            Set.of(BatchStatus.CONFIRMED, BatchStatus.REVERSED);
    private static final List<BatchKind> EFFECTIVE_KINDS =
            List.of(BatchKind.NETTING, BatchKind.ADJUSTMENT, BatchKind.REVERSAL);

    private final ClosingReportRepository reportRepo;
    private final ClosingBatchQueryRepository batchQueryRepo;
    private final NettingAgreementRepository agreementRepo;
    private final ReopenRequestRepository reopenRepo;
    private final ReopenDecisionRepository reopenDecisionRepo;
    private final DayLockService dayLock;
    private final DayGateCoordinator dayGate;
    private final ConcurrencyHooks hooks;

    public ClosingService(ClosingReportRepository reportRepo,
                          ClosingBatchQueryRepository batchQueryRepo,
                          NettingAgreementRepository agreementRepo,
                          ReopenRequestRepository reopenRepo,
                          ReopenDecisionRepository reopenDecisionRepo,
                          DayLockService dayLock,
                          DayGateCoordinator dayGate,
                          ConcurrencyHooks hooks) {
        this.reportRepo = reportRepo;
        this.batchQueryRepo = batchQueryRepo;
        this.agreementRepo = agreementRepo;
        this.reopenRepo = reopenRepo;
        this.reopenDecisionRepo = reopenDecisionRepo;
        this.dayLock = dayLock;
        this.dayGate = dayGate;
        this.hooks = hooks;
    }

    private static Instant dayStart(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant dayEnd(LocalDate d) {
        return d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public static LocalDate dateOf(Instant t) {
        return t.atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** 该结算日是否处于锁定（已关账或再开账审批中）。供确认/撤销/更正末审调用。 */
    @Transactional(readOnly = true)
    public boolean isDateClosed(Instant confirmedAt) {
        LocalDate date = dateOf(confirmedAt);
        return reportRepo.countByDateAndStatusIn(date,
                List.of(ClosingStatus.CLOSED, ClosingStatus.REOPEN_PENDING)) > 0;
    }

    public void assertDateNotClosed(Instant confirmedAt, String action) {
        if (isDateClosed(confirmedAt)) {
            throw new ConflictException("结算日 " + dateOf(confirmedAt)
                    + " 已关账（或再开账审批中），禁止" + action + "，请先完成再开账审批");
        }
    }

    // ---------------- 关账 ----------------

    @Transactional
    public ClosingReport close(LocalDate date, String by) {
        Instant now = Instant.now();
        hooks.closeBeforeIntent(date);
        // 1) 独立短事务持有日行锁登记 PENDING 门（与确认取同一把锁），重复关账在此 409。
        com.treasury.clearing.domain.SettlementDayGate gate = dayGate.registerCloseIntent(date);
        hooks.closeAfterIntent(date);
        try {
            // 2) 主事务取日行锁：与确认严格串行。
            hooks.closeBeforeLock();
            dayLock.acquire(date);
            // 3) 持锁判定竞争结果：确认先拿锁会把 PENDING 置 SUPERSEDED。
            gate = dayGate.claim(date, gate.getId());
            // 4) 持锁检查有效报表（关账胜出后的重复关账）。
            List<ClosingReport> existing = reportRepo.findByDateForUpdate(date);
            boolean activeExists = existing.stream().anyMatch(r ->
                    r.getStatus() == ClosingStatus.CLOSED || r.getStatus() == ClosingStatus.REOPEN_PENDING);
            if (activeExists) {
                dayGate.cancelPending(gate.getId());
                throw new ConflictException("结算日 " + date + " 已存在有效关账，不能重复关账");
            }
            // 5) 建快照并置门 CLOSED，同一日锁临界区内完成（关账胜出）。
            ClosingReport report = buildAndSaveSnapshot(date, 1, null, by, now,
                    "CLR-" + UUID.randomUUID().toString().substring(0, 8), null, null);
            dayGate.completeClose(gate);
            return report;
        } catch (RuntimeException failure) {
            try {
                dayGate.cancelPending(gate.getId());
            } catch (RuntimeException ignored) {
                // best effort
            }
            throw failure;
        }
    }

    /** 汇总并落一条快照（关账首发或再开账新版本共用）。调用方须已持报表行锁。 */
    private ClosingReport buildAndSaveSnapshot(LocalDate date, int version, String prefix, String by,
                                               Instant now, String id, String reopenReason,
                                               String reopenBy) {
        List<ClearingBatch> batches = batchQueryRepo.findEffectiveBatchesForUpdate(
                dayStart(date), dayEnd(date), BatchStatus.CONFIRMED,
                List.of(BatchKind.NETTING, BatchKind.ADJUSTMENT, BatchKind.REVERSAL));
        // 同时把当日已 REVERSED 的原清算批次纳入（与其冲正批次净额相抵）
        List<ClearingBatch> reversedOriginals = batchQueryRepo.findEffectiveBatchesForUpdate(
                dayStart(date), dayEnd(date), BatchStatus.REVERSED, List.of(BatchKind.NETTING));
        Map<String, ClearingBatch> uniq = new LinkedHashMap<>();
        Stream.concat(batches.stream(), reversedOriginals.stream())
                .forEach(b -> uniq.putIfAbsent(b.getId(), b));
        List<ClearingBatch> all = new ArrayList<>(uniq.values());

        // 关账前置：当日不得有审批中的撤销/更正（批次状态直接反映）
        List<ClearingBatch> pending = batchQueryRepo.findEffectiveBatchesForUpdate(
                dayStart(date), dayEnd(date), BatchStatus.REVERSAL_PENDING,
                List.of(BatchKind.NETTING, BatchKind.ADJUSTMENT, BatchKind.REVERSAL));
        pending.addAll(batchQueryRepo.findEffectiveBatchesForUpdate(
                dayStart(date), dayEnd(date), BatchStatus.ADJUSTMENT_PENDING,
                List.of(BatchKind.NETTING, BatchKind.ADJUSTMENT, BatchKind.REVERSAL)));
        if (!pending.isEmpty()) {
            throw new ConflictException("结算日 " + date + " 存在审批中的撤销/差额更正批次（如 "
                    + pending.get(0).getId() + "），审批完成前不能关账");
        }

        // 聚合：key = 协议|币种|法人
        Map<String, LineAcc> acc = new LinkedHashMap<>();
        List<ContribAcc> contribs = new ArrayList<>();
        for (ClearingBatch b : all) {
            for (ClearingGroup g : b.getGroups()) {
                String key0 = g.getAgreementCode() + "|" + g.getClearingCurrency();
                // 现金按实体归集
                Map<String, BigDecimal> cashByEntity = new HashMap<>();
                for (ClearingEntry e : g.getEntries()) {
                    if (e.getType() == com.treasury.clearing.domain.PaymentType.NET_PAYMENT
                            || e.getType() == com.treasury.clearing.domain.PaymentType.ADJUSTMENT) {
                        cashByEntity.merge(e.getToEntity(), e.getAmount(), BigDecimal::add);
                        cashByEntity.merge(e.getFromEntity(), e.getAmount().negate(), BigDecimal::add);
                    }
                }
                // 每主体作为债权人的清偿张数（镜像组里债权/债务方已交换，张数相同，按债权人计一次）
                Map<String, Integer> dischargeByCreditor = new HashMap<>();
                for (InvoiceDischarge d : g.getDischarges()) {
                    dischargeByCreditor.merge(d.getCreditorCode(), 1, Integer::sum);
                }
                for (NetPosition p : g.getPositions()) {
                    String key = key0 + "|" + p.getEntityCode();
                    LineAcc l = acc.computeIfAbsent(key, k -> new LineAcc(
                            g.getAgreementCode(), g.getClearingCurrency(), p.getEntityCode()));
                    l.grossRecv = l.grossRecv.add(p.getGrossReceivable());
                    l.grossPay = l.grossPay.add(p.getGrossPayable());
                    l.net = l.net.add(p.getNetAmount());
                    l.cash = l.cash.add(cashByEntity.getOrDefault(p.getEntityCode(), BigDecimal.ZERO));
                    l.discharges += dischargeByCreditor.getOrDefault(p.getEntityCode(), 0);
                }
                // 现金涉及但不在 netPosition 的主体也补进行
                for (Map.Entry<String, BigDecimal> ce : cashByEntity.entrySet()) {
                    String key = key0 + "|" + ce.getKey();
                    acc.computeIfAbsent(key, k -> new LineAcc(
                            g.getAgreementCode(), g.getClearingCurrency(), ce.getKey()));
                    LineAcc l = acc.get(key);
                    if (l.touchedCash.add(ce.getKey())) {
                        l.cash = l.cash.add(ce.getValue());
                    }
                }
                BigDecimal groupCash = g.getEntries().stream()
                        .filter(e -> e.getType() == com.treasury.clearing.domain.PaymentType.NET_PAYMENT
                                || e.getType() == com.treasury.clearing.domain.PaymentType.ADJUSTMENT)
                        .map(ClearingEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal groupNet = g.getPositions().stream().map(NetPosition::getNetAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal gRecv = g.getPositions().stream().map(NetPosition::getGrossReceivable)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal gPay = g.getPositions().stream().map(NetPosition::getGrossPayable)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                contribs.add(new ContribAcc(b, g, gRecv, gPay, groupNet, groupCash,
                        g.getDischarges().size()));
            }
        }

        BigDecimal totalCash = acc.values().stream().map(l -> l.cash).filter(c -> c.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        ClosingReport report = new ClosingReport(id, date, version, ClosingStatus.CLOSED, by, now,
                reopenReason, reopenBy, all.size(), totalCash);

        int seq = 0;
        List<LineAcc> lines = new ArrayList<>(acc.values());
        lines.sort(Comparator.comparing((LineAcc l) -> l.agreement)
                .thenComparing(l -> l.currency).thenComparing(l -> l.entity));
        for (LineAcc l : lines) {
            report.addLine(new ClosingReportLine("CLN-" + UUID.randomUUID().toString().substring(0, 8),
                    report, l.agreement, l.currency, l.entity, l.net, l.grossRecv, l.grossPay,
                    l.cash, l.discharges));
        }
        int cseq = 0;
        contribs.sort(Comparator.comparing((ContribAcc c) -> c.group().getAgreementCode())
                .thenComparing(c -> c.group().getClearingCurrency())
                .thenComparing(c -> c.batch().getId()));
        for (ContribAcc c : contribs) {
            report.addContribution(new ClosingContribution(
                    "CLC-" + UUID.randomUUID().toString().substring(0, 8), report,
                    c.batch().getId(), c.batch().getKind(), c.batch().getLabel(),
                    c.group().getAgreementCode(), c.group().getClearingCurrency(),
                    c.grossRecv(), c.grossPay(), c.net(), c.cash(), c.discharges()));
        }
        try {
            reportRepo.saveAndFlush(report);
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("结算日 " + date + " 的报表版本 " + version
                    + " 已存在（并发关账/再开账被拒绝）");
        }
        return report;
    }

    // ---------------- 再开账 ----------------

    @Transactional
    public ReopenRequest requestReopen(LocalDate date, String reason, String by) {
        Instant now = Instant.now();
        dayLock.acquire(date); // 与确认/关账/末审串行
        List<ClosingReport> reports = reportRepo.findByDateForUpdate(date);
        ClosingReport current = reports.stream()
                .filter(r -> r.getStatus() == ClosingStatus.CLOSED)
                .max(Comparator.comparingInt(ClosingReport::getReportVersion))
                .orElseThrow(() -> new ConflictException("结算日 " + date + " 没有已关账快照，不能再开账"));
        if (reports.stream().anyMatch(r -> r.getStatus() == ClosingStatus.REOPEN_PENDING)) {
            throw new ConflictException("结算日 " + date + " 已有进行中的再开账申请，请勿重复提交");
        }
        if (!reopenRepo.findActiveByDateForUpdate(date,
                List.of(ReopenStatus.REQUESTED, ReopenStatus.PARTIALLY_APPROVED)).isEmpty()) {
            throw new ConflictException("结算日 " + date + " 已存在再开账申请");
        }

        Threshold t = resolveThreshold(date);
        current.moveToReopenPending();

        ReopenRequest req = new ReopenRequest("RPN-" + UUID.randomUUID().toString().substring(0, 8),
                date, current.getReportVersion(), reason, by != null ? by : "资金专员", now,
                t.required(), t.agreement(), t.threshold(), t.dayCash(), t.currency());
        try {
            reopenRepo.saveAndFlush(req);
            reportRepo.save(current);
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("该结算日的再开账申请已存在（并发被拒绝）");
        }
        return req;
    }

    @Transactional
    public ReopenRequest decide(String requestId, String approverRaw, String comment,
                                ReopenDecision.Outcome outcome) {
        Instant now = Instant.now();
        String approver = approverRaw != null && !approverRaw.isBlank() ? approverRaw.trim() : null;
        if (approver == null) {
            throw new IllegalArgumentException("审批人不能为空");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("审批结果不能为空");
        }

        // 先读申请定位结算日（不加锁），随后在该日统一互斥边界内完成决议/快照切换
        ReopenRequest ref = reopenRepo.findById(requestId)
                .orElseThrow(() -> new NoSuchElementException("再开账申请不存在: " + requestId));
        LocalDate date = ref.getSettlementDate();
        dayLock.acquire(date); // 与关账/确认/撤销更正末审串行

        ReopenRequest req = reopenRepo.findByIdForUpdate(requestId)
                .orElseThrow(() -> new NoSuchElementException("再开账申请不存在: " + requestId));
        List<ClosingReport> reports = reportRepo.findByDateForUpdate(date);
        ClosingReport current = reports.stream()
                .filter(r -> r.getStatus() == ClosingStatus.REOPEN_PENDING
                        || r.getStatus() == ClosingStatus.CLOSED)
                .max(Comparator.comparingInt(ClosingReport::getReportVersion))
                .orElseThrow(() -> new ConflictException("结算日 " + date + " 关账快照缺失"));

        if (req.getStatus() == ReopenStatus.PROCESSED) {
            throw new ConflictException("再开账已处理完成，新报表版本为 v" + req.getNewReportVersion()
                    + "，请勿重复审批");
        }
        if (req.getStatus() == ReopenStatus.REJECTED) {
            throw new ConflictException("再开账申请已驳回（终态）");
        }
        if (approver.equals(req.getRequestedBy())) {
            throw new ConflictException("申请人不能审批自己的再开账申请（四眼原则）：" + approver);
        }
        List<ReopenDecision> decisions = reopenDecisionRepo.listByRequest(req.getId());
        for (ReopenDecision d : decisions) {
            if (d.getApprover().equals(approver)) {
                throw new ConflictException("审批人 " + approver + " 已提交过再开账决议，不能重复提交");
            }
        }
        int seq = decisions.size() + 1;
        ReopenStatus before = req.getStatus();

        if (outcome == ReopenDecision.Outcome.REJECT) {
            req.reject(approver, now, comment);
            current.backToClosed();
            saveDecision(new ReopenDecision("RPD-" + UUID.randomUUID().toString().substring(0, 8),
                    req, seq, ReopenDecision.Outcome.REJECT, approver, comment, now,
                    before, ReopenStatus.REJECTED, ClosingStatus.REOPEN_PENDING,
                    ClosingStatus.CLOSED, null));
            reopenRepo.save(req);
            reportRepo.save(current);
            return req;
        }

        boolean willFinalize =
                req.getApprovalsReceived() + 1 >= req.getRequiredApprovals();
        if (!willFinalize) {
            req.recordApproval();
            saveDecision(new ReopenDecision("RPD-" + UUID.randomUUID().toString().substring(0, 8),
                    req, seq, ReopenDecision.Outcome.APPROVE, approver, comment, now,
                    before, ReopenStatus.PARTIALLY_APPROVED,
                    ClosingStatus.REOPEN_PENDING, ClosingStatus.REOPEN_PENDING, null));
            reopenRepo.save(req);
            return req;
        }

        // 末审：旧版本 SUPERSEDED + 新版本 CLOSED 同事务原子完成
        int newVersion = req.getFromReportVersion() + 1;
        String newReportId = "CLR-" + UUID.randomUUID().toString().substring(0, 8);
        current.supersede();
        // 必须先把旧行 SUPERSEDED 落库（释放部分唯一索引），才能插入新的 CLOSED 行
        reportRepo.saveAndFlush(current);
        ClosingReport fresh = buildAndSaveSnapshot(date, newVersion, "CLR", approver, now,
                newReportId, req.getReason(), req.getRequestedBy());

        req.markProcessed(newReportId, newVersion, fresh.getBatchCount(), approver, now);
        saveDecision(new ReopenDecision("RPD-" + UUID.randomUUID().toString().substring(0, 8),
                req, seq, ReopenDecision.Outcome.APPROVE, approver, comment, now,
                before, ReopenStatus.PROCESSED,
                ClosingStatus.REOPEN_PENDING, ClosingStatus.CLOSED, newReportId));
        reopenRepo.save(req);
        return req;
    }

    private void saveDecision(ReopenDecision d) {
        try {
            reopenDecisionRepo.saveAndFlush(d);
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("审批人 " + d.getApprover() + " 的再开账决议已存在（并发被拒绝）");
        }
    }

    /** 按当日各组现金清偿绝对额与协议门槛决定一审/双审。 */
    private Threshold resolveThreshold(LocalDate date) {
        List<ClearingBatch> batches = batchQueryRepo.findEffectiveBatchesForUpdate(
                dayStart(date), dayEnd(date), BatchStatus.CONFIRMED, EFFECTIVE_KINDS);
        batches.addAll(batchQueryRepo.findEffectiveBatchesForUpdate(
                dayStart(date), dayEnd(date), BatchStatus.REVERSED, List.of(BatchKind.NETTING)));
        int required = 1;
        String agreement = null;
        BigDecimal threshold = null;
        BigDecimal maxCash = BigDecimal.ZERO;
        String currency = null;
        Map<String, BigDecimal> perGroup = new HashMap<>();
        Map<String, String> ccyMap = new HashMap<>();
        for (ClearingBatch b : batches) {
            for (ClearingGroup g : b.getGroups()) {
                BigDecimal cash = g.getEntries().stream()
                        .filter(e -> e.getType() == com.treasury.clearing.domain.PaymentType.NET_PAYMENT
                                || e.getType() == com.treasury.clearing.domain.PaymentType.ADJUSTMENT)
                        .map(ClearingEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                perGroup.merge(g.getAgreementCode(), cash, BigDecimal::add);
                ccyMap.put(g.getAgreementCode(), g.getClearingCurrency());
            }
        }
        for (Map.Entry<String, BigDecimal> e : perGroup.entrySet()) {
            NettingAgreement a = agreementRepo.findById(e.getKey()).orElse(null);
            BigDecimal t = a != null ? a.getDualApprovalThreshold() : null;
            BigDecimal abs = e.getValue().abs();
            if (t != null && abs.compareTo(t) >= 0) {
                required = 2;
                if (abs.compareTo(maxCash) > 0) {
                    maxCash = abs;
                    agreement = e.getKey();
                    threshold = t;
                    currency = ccyMap.get(e.getKey());
                }
            }
        }
        return new Threshold(required, agreement, threshold, maxCash, currency);
    }

    @Transactional(readOnly = true)
    public List<ClosingReport> listReports(LocalDate date) {
        return reportRepo.findBySettlementDateOrderByReportVersionDesc(date);
    }

    private record Threshold(int required, String agreement, BigDecimal threshold,
                             BigDecimal dayCash, String currency) {
    }

    private static final class LineAcc {
        final String agreement;
        final String currency;
        final String entity;
        BigDecimal grossRecv = BigDecimal.ZERO;
        BigDecimal grossPay = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal cash = BigDecimal.ZERO;
        int discharges = 0;
        java.util.Set<String> touchedCash = new java.util.HashSet<>();
        LineAcc(String agreement, String currency, String entity) {
            this.agreement = agreement;
            this.currency = currency;
            this.entity = entity;
        }
    }

    private record ContribAcc(ClearingBatch batch, ClearingGroup group, BigDecimal grossRecv,
                              BigDecimal grossPay, BigDecimal net, BigDecimal cash, int discharges) {
    }
}
