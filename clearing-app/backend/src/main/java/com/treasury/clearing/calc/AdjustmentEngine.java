package com.treasury.clearing.calc;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 差额净额引擎（纯函数，BigDecimal/HALF_UP）。
 *
 * <p>只对被更正发票产生的净头寸差异 {@code delta} 重新多边轧差：
 * 每张发票金额 +Δ（债权人受益）等价于债权人 +Δ、债务人 −Δ，因此组内 Δ 合计天然为 0。
 * 用与 {@link NettingEngine} 相同的贪心配对，把差额付款笔数压到最小。
 */
@Component
public class AdjustmentEngine {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public AdjustmentGroupResult compute(String agreementCode, String clearingCurrency,
                                         List<CorrectionInput> inputs) {
        Map<String, BigDecimal> deltaNet = new LinkedHashMap<>();
        for (CorrectionInput c : inputs) {
            deltaNet.merge(c.creditor(), c.delta(), BigDecimal::add);
            deltaNet.merge(c.debtor(), c.delta().negate(), BigDecimal::add);
        }

        BigDecimal sum = deltaNet.values().stream().reduce(ZERO, BigDecimal::add);
        if (sum.signum() != 0) {
            throw new IllegalStateException("差额净头寸不守恒，合计=" + sum
                    + "，组=" + agreementCode + "/" + clearingCurrency);
        }

        Deque<Balance> receivers = new ArrayDeque<>();
        Deque<Balance> payers = new ArrayDeque<>();
        deltaNet.forEach((entity, net) -> {
            int cmp = net.compareTo(ZERO);
            if (cmp > 0) {
                receivers.add(new Balance(entity, net));
            } else if (cmp < 0) {
                payers.add(new Balance(entity, net.abs()));
            }
        });

        List<AdjustmentInstruction> instructions = new ArrayList<>();
        int guard = 0;
        while (!receivers.isEmpty() && !payers.isEmpty()) {
            Balance r = receivers.peekFirst();
            Balance p = payers.peekFirst();
            BigDecimal transfer = r.amount.min(p.amount);
            if (transfer.signum() != 0) {
                instructions.add(new AdjustmentInstruction(
                        p.entity, r.entity, transfer, clearingCurrency,
                        "发票更正差额结算（模拟台账，不接银行，不清偿原债权）"));
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
                throw new IllegalStateException("差额配对未收敛");
            }
        }

        if (instructions.isEmpty()) {
            // 更正额在组内恰好对冲（如等额环内等额增减），无差额资金。
            String a = inputs.get(0).debtor();
            String b = inputs.get(0).creditor();
            instructions.add(new AdjustmentInstruction(
                    a, b, ZERO, clearingCurrency,
                    "更正差额在组内互抵，无差额资金收付（" + inputs.size() + " 张发票更正）"));
        }

        return new AdjustmentGroupResult(agreementCode, clearingCurrency,
                deltaNet, instructions);
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
