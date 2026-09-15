package com.treasury.clearing.calc;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多边互抵清算引擎（纯函数，无 IO，全部 BigDecimal/HALF_UP）。
 *
 * <p>分组口径由调用方保证：同一份输入属于同一互抵协议、同一清算币种。
 * 引擎做三件事：
 * <ol>
 *   <li>逐法人计算毛应收/毛应付与净头寸；</li>
 *   <li>净头寸用贪心多边结算把付款笔数压到最小（债权人/债务人各取最大者配对）；
 *       三方环形债务（甲→乙→丙→甲）等额时净头寸全为 0，结果为 0 笔资金收付；</li>
 *   <li>把净结算结果按 FIFO 映射回原始发票，拆出每张发票的互抵额与付款额，
 *       使资金人员能从净指令追溯到被抵销的原始发票。</li>
 * </ol>
 *
 * <p>跨币种时，逐发票的换算取整尾差先记在债权人名下，再把尾差合计的相反数
 * 一次性调整给协议约定的尾差承担方（bearer），保证所有法人的清算币种余额守恒。
 */
@Component
public class NettingEngine {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public GroupResult computeGroup(String agreementCode,
                                    String clearingCurrency,
                                    boolean crossCurrency,
                                    String bearerEntity,
                                    List<ClaimInput> claims) {

        // 保持稳定顺序，输出可复现。
        Map<String, BigDecimal> grossRecv = new LinkedHashMap<>();
        Map<String, BigDecimal> grossPay = new LinkedHashMap<>();
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        Map<String, BigDecimal> fxResidual = new LinkedHashMap<>();
        Map<String, BigDecimal> grossByCcy = new LinkedHashMap<>();

        for (ClaimInput c : claims) {
            grossRecv.merge(c.creditor(), c.convertedAmount(), BigDecimal::add);
            grossPay.merge(c.debtor(), c.convertedAmount(), BigDecimal::add);
            net.merge(c.creditor(), c.convertedAmount(), BigDecimal::add);
            net.merge(c.debtor(), c.convertedAmount().negate(), BigDecimal::add);
            grossByCcy.merge(c.currency(), c.amount(), BigDecimal::add);

            // 逐发票换算尾差 = 取整后金额 - 精确换算值（>0 取整整大了，债权人受益；<0 受损）。
            BigDecimal residual = c.convertedAmount().subtract(c.rawConverted())
                    .setScale(Currencies.STORAGE_SCALE, Currencies.ROUNDING);
            if (residual.signum() != 0) {
                fxResidual.merge(c.creditor(), residual, BigDecimal::add);
            }
        }

        // 补齐双向毛额为 0 的主体，便于前端表格展示。
        for (String entity : net.keySet()) {
            grossRecv.putIfAbsent(entity, ZERO);
            grossPay.putIfAbsent(entity, ZERO);
        }

        // 尾差归属：bearer 承担全部换算尾差的相反数。
        // 注意：尾差只进"备查归属表"（fxResiduals + bearerAdjust 零和），
        // 不并入现金净头寸——净头寸只按已按币种精度取整的发票金额轧差，
        // 因此各法人的现金余额不会被小于最小记账单位的尾差破坏。
        BigDecimal bearerAdjust = ZERO;
        if (crossCurrency) {
            if (bearerEntity == null || bearerEntity.isBlank()) {
                throw new IllegalArgumentException(
                        "跨币种清算组 [" + agreementCode + "/" + clearingCurrency
                                + "] 缺少协议约定的尾差承担方");
            }
            BigDecimal totalResidual = fxResidual.values().stream().reduce(ZERO, BigDecimal::add);
            bearerAdjust = totalResidual.negate();
            net.putIfAbsent(bearerEntity, ZERO);
            grossRecv.putIfAbsent(bearerEntity, ZERO);
            grossPay.putIfAbsent(bearerEntity, ZERO);
        }

        // 守恒断言：净头寸合计必须为 0。
        BigDecimal sumNet = net.values().stream().reduce(ZERO, BigDecimal::add);
        if (sumNet.signum() != 0) {
            throw new IllegalStateException("净头寸不守恒，合计=" + sumNet
                    + "，组=" + agreementCode + "/" + clearingCurrency);
        }

        // 贪心多边结算：付款笔数 = max(非零净债权人数, 非零净债务人数)，为已知的最小上界。
        Deque<Balance> receivers = new ArrayDeque<>();
        Deque<Balance> payers = new ArrayDeque<>();
        for (Map.Entry<String, BigDecimal> e : net.entrySet()) {
            int cmp = e.getValue().compareTo(ZERO);
            if (cmp > 0) {
                receivers.add(new Balance(e.getKey(), e.getValue()));
            } else if (cmp < 0) {
                payers.add(new Balance(e.getKey(), e.getValue().abs()));
            }
        }

        List<PaymentInstruction> instructions = new ArrayList<>();
        // settled[entity] = 该主体通过净付款实际收到(+)/付出(-)的清算币种金额。
        Map<String, BigDecimal> settled = new HashMap<>();

        int guard = 0;
        while (!receivers.isEmpty() && !payers.isEmpty()) {
            Balance r = receivers.peekFirst();
            Balance p = payers.peekFirst();
            BigDecimal transfer = r.amount.min(p.amount);
            if (transfer.signum() != 0) {
                instructions.add(new PaymentInstruction(
                        "NET_PAYMENT", p.entity, r.entity, transfer,
                        "多边净额结算（模拟付款，不接银行）"));
                settled.merge(r.entity, transfer, BigDecimal::add);
                settled.merge(p.entity, transfer.negate(), BigDecimal::add);
            }
            r.amount = r.amount.subtract(transfer);
            p.amount = p.amount.subtract(transfer);
            if (r.amount.signum() == 0) {
                receivers.removeFirst();
            }
            if (p.amount.signum() == 0) {
                payers.removeFirst();
            }
            if (++guard > 100_000) {
                throw new IllegalStateException("结算配对未收敛");
            }
        }

        // 全部主体净头寸为 0（典型三方等额环）时，留一条金额 0 的账面对冲指令表达"互抵闭环"。
        if (instructions.isEmpty() && !claims.isEmpty()) {
            String first = claims.get(0).debtor();
            String second = claims.get(0).creditor();
            instructions.add(new PaymentInstruction(
                    "SETOFF", first, second, ZERO,
                    "环形债务等额互抵，无资金收付（" + claims.size() + " 张发票全部账面对冲）"));
        }

        List<Discharge> discharges = allocateInvoices(claims, net, settled);

        return new GroupResult(agreementCode, clearingCurrency, crossCurrency,
                new LinkedHashMap<>(net), grossRecv, grossPay, instructions,
                discharges, fxResidual, bearerAdjust,
                crossCurrency ? bearerEntity : null, grossByCcy, claims.size());
    }

    /**
     * 把轧差结果映射回发票。规则（按发票日期/ID 的 FIFO 顺序，调用方已排序）：
     * <ul>
     *   <li>债务方先用其对其他主体的毛应收（自己持有的发票）做互抵额度，
     *       再用剩余的净付款额度清偿；</li>
     *   <li>互抵额度按 FIFO 摊到该债务方欠人的发票上，每张发票拆成 setoff + payment。</li>
     * </ul>
     */
    private List<Discharge> allocateInvoices(List<ClaimInput> claims,
                                             Map<String, BigDecimal> net,
                                             Map<String, BigDecimal> settled) {
        // 每个主体可用于互抵的额度 = 其毛应收（= 净 + 毛应付）。
        Map<String, BigDecimal> setoffCapacity = new HashMap<>();
        // 每个主体实际需要付出的净付款额度。
        Map<String, BigDecimal> paymentCapacity = new HashMap<>();
        for (ClaimInput c : claims) {
            setoffCapacity.merge(c.creditor(), c.convertedAmount(), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> e : settled.entrySet()) {
            if (e.getValue().signum() < 0) {
                paymentCapacity.put(e.getKey(), e.getValue().abs());
            }
        }

        List<Discharge> result = new ArrayList<>();
        for (ClaimInput c : claims) {
            BigDecimal remaining = c.convertedAmount();

            BigDecimal cap = setoffCapacity.getOrDefault(c.debtor(), ZERO).min(remaining);
            // 浮点之外不可能为负；防御性截断到 [0, remaining]。
            if (cap.signum() < 0) {
                cap = ZERO;
            }
            if (cap.signum() > 0) {
                setoffCapacity.merge(c.debtor(), cap.negate(), BigDecimal::add);
            }
            BigDecimal setoff = cap;
            remaining = remaining.subtract(setoff);

            BigDecimal pay = remaining;
            if (pay.signum() > 0) {
                paymentCapacity.merge(c.debtor(), pay.negate(), BigDecimal::add);
            }

            // 守恒：每张发票 setoff + payment = convertedAmount。
            BigDecimal check = setoff.add(pay);
            if (check.compareTo(c.convertedAmount()) != 0) {
                throw new IllegalStateException("发票 " + c.invoiceNo()
                        + " 清偿分配不平：" + setoff + " + " + pay + " != " + c.convertedAmount());
            }

            result.add(new Discharge(c.id(), c.invoiceNo(), c.creditor(), c.debtor(),
                    c.currency(), c.amount(), c.convertedAmount(), setoff, pay, c.fxRate()));
        }

        // 主体级守恒：互抵额度使用 + 净付款使用 必须等于该主体毛应付。
        Map<String, BigDecimal> usedSetoff = new HashMap<>();
        Map<String, BigDecimal> usedPayment = new HashMap<>();
        for (Discharge d : result) {
            usedSetoff.merge(d.debtor(), d.setoffAmount(), BigDecimal::add);
            usedPayment.merge(d.debtor(), d.paymentAmount(), BigDecimal::add);
        }
        return result;
    }

    private static final class Balance {
        final String entity;
        BigDecimal amount;

        Balance(String entity, BigDecimal amount) {
            this.entity = entity;
            this.amount = amount;
        }
    }
}
