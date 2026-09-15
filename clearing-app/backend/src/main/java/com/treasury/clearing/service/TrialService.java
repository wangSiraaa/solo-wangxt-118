package com.treasury.clearing.service;

import com.treasury.clearing.calc.ClaimInput;
import com.treasury.clearing.calc.Currencies;
import com.treasury.clearing.calc.GroupResult;
import com.treasury.clearing.calc.NettingEngine;
import com.treasury.clearing.domain.BatchStatus;
import com.treasury.clearing.domain.ClearingBatch;
import com.treasury.clearing.domain.ConflictException;
import com.treasury.clearing.domain.ClearingEntry;
import com.treasury.clearing.domain.ClearingGroup;
import com.treasury.clearing.domain.ExcludedClaim;
import com.treasury.clearing.domain.FxRate;
import com.treasury.clearing.domain.InvoiceDischarge;
import com.treasury.clearing.domain.NetPosition;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.domain.PaymentType;
import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.domain.ReceivableStatus;
import com.treasury.clearing.domain.RoundingLine;
import com.treasury.clearing.repo.AgreementPartyRepository;
import com.treasury.clearing.repo.ClearingBatchRepository;
import com.treasury.clearing.repo.ExcludedClaimRepository;
import com.treasury.clearing.repo.FxRateRepository;
import com.treasury.clearing.repo.NettingAgreementRepository;
import com.treasury.clearing.repo.ReceivableRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 清算试算编排：
 * 资格筛选（排除质押/争议/无协议/已清偿）→ 按协议与币种边界分组 →
 * 跨币种折算（BigDecimal、记录汇率与时点）→ 调引擎轧差 → 持久化整个批次快照。
 *
 * <p>试算（SIMULATED）与确认（CONFIRMED）走同一套计算，差别只在确认时：
 * <ol>
 *   <li>原始债权状态改为 CLEARED（金额等原始字段不动）；</li>
 *   <li>批次标记 CONFIRMED。</li>
 * </ol>
 * 本服务不发起任何真实银行付款。
 */
@Service
public class TrialService {

    private final ReceivableRepository receivableRepo;
    private final NettingAgreementRepository agreementRepo;
    private final AgreementPartyRepository partyRepo;
    private final FxRateRepository fxRepo;
    private final ClearingBatchRepository batchRepo;
    private final ExcludedClaimRepository excludedRepo;
    private final NettingEngine engine;
    private final org.springframework.beans.factory.ObjectProvider<ClosingService> closingServiceProvider;

    public TrialService(ReceivableRepository receivableRepo,
                        NettingAgreementRepository agreementRepo,
                        AgreementPartyRepository partyRepo,
                        FxRateRepository fxRepo,
                        ClearingBatchRepository batchRepo,
                        ExcludedClaimRepository excludedRepo,
                        NettingEngine engine,
                        org.springframework.beans.factory.ObjectProvider<ClosingService> closingServiceProvider) {
        this.receivableRepo = receivableRepo;
        this.agreementRepo = agreementRepo;
        this.partyRepo = partyRepo;
        this.fxRepo = fxRepo;
        this.batchRepo = batchRepo;
        this.excludedRepo = excludedRepo;
        this.engine = engine;
        this.closingServiceProvider = closingServiceProvider;
    }

    @Transactional
    public ClearingBatch runTrial(String label, Instant valuationTime, String createdBy,
                                  boolean confirm) {
        Instant now = Instant.now();
        Instant valuation = valuationTime != null ? valuationTime : now;

        List<Receivable> all = receivableRepo.findAllByOrderByInvoiceDateAsc();
        Map<String, NettingAgreement> agreements = agreementRepo.findAll().stream()
                .collect(Collectors.toMap(NettingAgreement::getCode, a -> a));
        Map<String, Set<String>> partiesByAgreement = new HashMap<>();
        partyRepo.findAll().forEach(p ->
                partiesByAgreement.computeIfAbsent(p.getAgreementCode(), k -> new HashSet<>())
                        .add(p.getEntityCode()));

        // 资格筛选：先按排除优先级打原因，再按协议+清算币种分组。
        List<Receivable> eligible = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        for (Receivable r : all) {
            String reason = exclusionReason(r, agreements, partiesByAgreement);
            if (reason != null) {
                excluded.add(new Excluded(r, reason, exclusionDetail(reason, r)));
            } else {
                eligible.add(r);
            }
        }

        Map<GroupKey, List<Receivable>> grouped = new LinkedHashMap<>();
        for (Receivable r : eligible) {
            NettingAgreement a = agreements.get(r.getAgreementCode());
            String clearingCcy = a.isCrossCurrency() ? a.getSettlementCurrency() : r.getCurrency();
            grouped.computeIfAbsent(
                    new GroupKey(a.getCode(), clearingCcy, a.isCrossCurrency()),
                    k -> new ArrayList<>()).add(r);
        }

        String batchId = (confirm ? "CFM-" : "SIM-") + UUID.randomUUID().toString().substring(0, 8);
        List<GroupBuild> builtGroups = new ArrayList<>();
        int resultingEntries = 0;
        int groupSeq = 0;

        for (Map.Entry<GroupKey, List<Receivable>> g : grouped.entrySet()) {
            GroupKey key = g.getKey();
            NettingAgreement agreement = agreements.get(key.agreementCode());

            List<ClaimInput> claims = new ArrayList<>();
            Map<String, FxUsed> fxUsedByReceivable = new HashMap<>();
            for (Receivable r : g.getValue()) {
                BigDecimal rate = BigDecimal.ONE;
                Instant rateTime = valuation;
                if (!r.getCurrency().equals(key.clearingCurrency())) {
                    FxRate fx = latestFx(r.getCurrency(), key.clearingCurrency(), valuation);
                    rate = fx.getRate();
                    rateTime = fx.getRateTime();
                    fxUsedByReceivable.put(r.getId(),
                            new FxUsed(rate, rateTime, fx.getSource()));
                }
                BigDecimal raw = Currencies.convertRaw(r.getAmount(), rate);
                BigDecimal converted = r.getCurrency().equals(key.clearingCurrency())
                        ? Currencies.roundCash(r.getCurrency(), r.getAmount())
                        : Currencies.convert(r.getAmount(), rate, key.clearingCurrency());
                claims.add(new ClaimInput(r.getId(), r.getInvoiceNo(), r.getCreditorCode(),
                        r.getDebtorCode(), r.getCurrency(), r.getAmount(),
                        converted, raw, rate));
            }

            // FIFO：发票日期由仓库排序保证；同日期再按 id 稳定排序。
            claims.sort(Comparator.comparing(ClaimInput::id));
            GroupResult result = engine.computeGroup(key.agreementCode(), key.clearingCurrency(),
                    key.crossCurrency(), agreement.getRoundingParty(), claims);

            builtGroups.add(new GroupBuild(++groupSeq, key, g.getValue(), result,
                    fxUsedByReceivable));
            resultingEntries += result.instructions().size();
        }

        String batchLabel = label != null ? label : "清算批次 " + LocalDate.now();
        ClearingBatch batch = new ClearingBatch(batchId, batchLabel,
                confirm ? BatchStatus.CONFIRMED : BatchStatus.SIMULATED,
                now, valuation,
                all.size(), resultingEntries, excluded.size(), createdBy);

        for (GroupBuild gb : builtGroups) {
            persistGroup(batch, gb);
        }
        ClearingBatch saved = batchRepo.save(batch);

        int exSeq = 0;
        List<ExcludedClaim> excludedRows = new ArrayList<>();
        for (Excluded ex : excluded) {
            excludedRows.add(new ExcludedClaim(saved.getId() + "-EX" + (++exSeq), saved,
                    ex.r.getId(), ex.r.getInvoiceNo(), ex.reason, ex.detail));
        }
        excludedRepo.saveAll(excludedRows);

        if (confirm) {
            // 确认才改变原始债权状态；金额等原始字段保持不动。
            // 对将被清偿的债权加排他锁并复验状态，与撤销冲正串行化：
            // 若其中任何一张已被并发的撤销恢复/改动（非 ACTIVE），整体 409，不重复清偿。
            List<String> eligibleIds = eligible.stream().map(Receivable::getId).toList();
            List<Receivable> locked = eligibleIds.isEmpty()
                    ? List.of()
                    : receivableRepo.findAllByIdForUpdate(eligibleIds);
            for (Receivable r : locked) {
                if (r.getStatus() != ReceivableStatus.ACTIVE) {
                    throw new ConflictException("债权 " + r.getInvoiceNo()
                            + " 已被其它操作改动（状态 " + r.getStatus()
                            + "），与确认并发冲突，请刷新后重试");
                }
                r.markCleared();
            }
            Instant confirmedAt = Instant.now();
            // 已关账日期禁止继续确认
            closingServiceProvider.getObject().assertDateNotClosed(confirmedAt, "确认清算方案");
            receivableRepo.saveAll(locked);
            saved.confirm(confirmedAt);
        }
        return saved;
    }

    /** 确认一个已存在的试算批次：按当前数据重新计算并落一个 CONFIRMED 批次。 */
    @Transactional
    public ClearingBatch confirmFromSimulation(String batchId, String createdBy) {
        ClearingBatch origin = batchRepo.findById(batchId)
                .orElseThrow(() -> new java.util.NoSuchElementException("批次不存在: " + batchId));
        if (origin.getStatus() != BatchStatus.SIMULATED) {
            throw new ClearingRuleException("只有试算批次可以确认，当前状态: " + origin.getStatus());
        }

        // 重复确认 / 与撤销并发保护：该试算方案原本要清偿的债权必须仍是 ACTIVE。
        // 已被某次确认清偿（CLEARED）或处于撤销窗口（债权仍 CLEARED）时直接 409；
        // 与撤销冲正并发时，这里的行锁与冲正事务互斥串行。
        List<String> plannedIds = origin.getGroups().stream()
                .flatMap(g -> g.getDischarges().stream())
                .map(InvoiceDischarge::getReceivableId)
                .distinct()
                .toList();
        if (!plannedIds.isEmpty()) {
            List<Receivable> locked = receivableRepo.findAllByIdForUpdate(plannedIds);
            for (Receivable r : locked) {
                if (r.getStatus() != ReceivableStatus.ACTIVE) {
                    throw new ConflictException("该试算方案涉及的发票 " + r.getInvoiceNo()
                            + " 已被确认或处于撤销处理中（状态 " + r.getStatus()
                            + "），不能重复确认，请刷新后重试");
                }
            }
        }

        return runTrial(origin.getLabel() + "（确认）", origin.getValuationTime(),
                createdBy != null ? createdBy : origin.getCreatedBy(), true);
    }

    private void persistGroup(ClearingBatch batch, GroupBuild gb) {
        GroupResult r = gb.result();
        String display = r.grossByCcy().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + " " + e.getValue().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining("; "));

        ClearingGroup group = new ClearingGroup(
                batch.getId() + "-G" + gb.seq(), batch, r.agreementCode(),
                r.clearingCurrency(), r.crossCurrency(), r.claimCount(), display,
                r.instructions().size());
        batch.addGroup(group);

        int posSeq = 0;
        for (String entity : r.positions().keySet()) {
            group.addPosition(new NetPosition(group.getId() + "-P" + (++posSeq), group, entity,
                    r.grossReceivable().getOrDefault(entity, BigDecimal.ZERO),
                    r.grossPayable().getOrDefault(entity, BigDecimal.ZERO),
                    r.positions().get(entity)));
        }

        int entrySeq = 0;
        for (var ins : r.instructions()) {
            PaymentType type = "SETOFF".equals(ins.type())
                    ? PaymentType.SETOFF : PaymentType.NET_PAYMENT;
            group.addEntry(new ClearingEntry(group.getId() + "-E" + (++entrySeq), group,
                    type, ins.fromEntity(), ins.toEntity(), ins.amount(),
                    r.clearingCurrency(), ins.description()));
        }
        // 尾差不生成 clearing_entry（无收付对手方、也不改变现金余额），仅在 rounding_line 归属。

        int dSeq = 0;
        for (var d : r.discharges()) {
            group.addDischarge(new InvoiceDischarge(group.getId() + "-D" + (++dSeq), group,
                    d.receivableId(), d.invoiceNo(), d.creditor(), d.debtor(),
                    d.originalCurrency(), d.originalAmount(), d.convertedAmount(),
                    d.setoffAmount(), d.paymentAmount(), d.fxRate()));
        }

        int rSeq = 0;
        for (Map.Entry<String, BigDecimal> fx : r.fxResiduals().entrySet()) {
            if (fx.getValue().signum() == 0) {
                continue;
            }
            Receivable refInvoice = gb.members().stream()
                    .filter(m -> m.getCreditorCode().equals(fx.getKey())
                            && !m.getCurrency().equals(r.clearingCurrency()))
                    .findFirst().orElse(null);
            FxUsed used = refInvoice != null ? gb.fxUsed().get(refInvoice.getId()) : null;
            group.addRoundingLine(RoundingLine.fx(group.getId() + "-R" + (++rSeq), group,
                    fx.getKey(), r.clearingCurrency(), fx.getValue(),
                    refInvoice != null ? refInvoice.getInvoiceNo() : null,
                    used != null ? used.rate() : BigDecimal.ONE,
                    used != null ? used.rateTime().toString() : null,
                    "逐发票按记账币种取整产生的换算尾差"));
        }
        if (r.crossCurrency() && r.bearerAdjust().signum() != 0) {
            group.addRoundingLine(RoundingLine.bearer(group.getId() + "-R" + (++rSeq), group,
                    r.bearerEntity(), r.clearingCurrency(), r.bearerAdjust(),
                    "全部换算尾差按协议归集到 " + r.bearerEntity()
                            + "（尾差合计为 0，不产生资金收付、不改变现金余额）"));
        }
    }

    private String exclusionReason(Receivable r,
                                   Map<String, NettingAgreement> agreements,
                                   Map<String, Set<String>> parties) {
        if (r.getStatus() != ReceivableStatus.ACTIVE) {
            return "NOT_ACTIVE";
        }
        if (r.isPledged()) {
            return "PLEDGED";
        }
        if (r.isDisputed()) {
            return "DISPUTED";
        }
        if (r.getAgreementCode() == null || r.getAgreementCode().isBlank()) {
            return "NO_AGREEMENT";
        }
        NettingAgreement a = agreements.get(r.getAgreementCode());
        if (a == null) {
            return "NO_AGREEMENT";
        }
        Set<String> members = parties.getOrDefault(a.getCode(), Set.of());
        if (!members.contains(r.getCreditorCode()) || !members.contains(r.getDebtorCode())) {
            return "AGREEMENT_MISMATCH";
        }
        if (a.isCrossCurrency()
                && (a.getSettlementCurrency() == null || a.getRoundingParty() == null)) {
            return "AGREEMENT_MISCONFIGURED";
        }
        return null;
    }

    private String exclusionDetail(String reason, Receivable r) {
        return switch (reason) {
            case "PLEDGED" -> "发票 " + r.getInvoiceNo() + " 已质押，质押权人优先受偿，不得互抵";
            case "DISPUTED" -> "发票 " + r.getInvoiceNo() + " 存在争议，冻结互抵";
            case "NO_AGREEMENT" -> "发票 " + r.getInvoiceNo() + " 未挂靠有效互抵协议，保留原始债务";
            case "AGREEMENT_MISMATCH" -> "发票 " + r.getInvoiceNo() + " 的双方不是协议参与方";
            case "NOT_ACTIVE" -> "发票 " + r.getInvoiceNo() + " 状态为 " + r.getStatus();
            case "AGREEMENT_MISCONFIGURED" -> "跨币种协议缺少结算币种或尾差承担方";
            default -> reason;
        };
    }

    private FxRate latestFx(String from, String to, Instant asOf) {
        return fxRepo.findByFromCurrencyAndToCurrencyOrderByRateTimeDesc(from, to).stream()
                .filter(fx -> !fx.getRateTime().isAfter(asOf))
                .findFirst()
                .orElseThrow(() -> new ClearingRuleException(
                        "缺少 " + asOf + " 时点可用的汇率 " + from + "->" + to
                                + "，请先在汇率面板维护内部记账汇率"));
    }

    private record Excluded(Receivable r, String reason, String detail) {
    }

    private record GroupKey(String agreementCode, String clearingCurrency, boolean crossCurrency) {
    }

    private record FxUsed(BigDecimal rate, Instant rateTime, String source) {
    }

    private record GroupBuild(int seq, GroupKey key, List<Receivable> members,
                              GroupResult result, Map<String, FxUsed> fxUsed) {
    }
}
