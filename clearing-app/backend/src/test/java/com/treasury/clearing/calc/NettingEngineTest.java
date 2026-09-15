package com.treasury.clearing.calc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NettingEngineTest {

    private final NettingEngine engine = new NettingEngine();

    private static ClaimInput claim(String id, String creditor, String debtor, String amount) {
        BigDecimal a = new BigDecimal(amount);
        return new ClaimInput(id, "INV-" + id, creditor, debtor, "CNY", a,
                Currencies.roundCash("CNY", a), Currencies.convertRaw(a, BigDecimal.ONE),
                BigDecimal.ONE);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** 甲->乙、乙->丙、丙->甲 各 100 万：净头寸全 0，0 笔资金收付，全部发票互抵。 */
    @Test
    void threePartyEqualRing_collapsesToZeroPayments() {
        List<ClaimInput> claims = List.of(
                claim("1", "A", "B", "1000000.00"),
                claim("2", "B", "C", "1000000.00"),
                claim("3", "C", "A", "1000000.00"));

        GroupResult r = engine.computeGroup("NA", "CNY", false, null, claims);

        assertThat(r.positions().values()).allSatisfy(n -> assertThat(n).isEqualByComparingTo("0"));
        // 唯一一条是金额 0 的账面对冲闭环指令，没有任何资金付款。
        assertThat(r.instructions()).hasSize(1);
        assertThat(r.instructions().get(0).type()).isEqualTo("SETOFF");
        assertThat(r.instructions().get(0).amount()).isEqualByComparingTo("0");
        assertThat(r.instructions().stream().filter(i -> i.type().equals("NET_PAYMENT")).count())
                .isZero();
        // 3 张原始发票全部能追溯到互抵分配，且每张 setoff+payment=发票额。
        assertThat(r.discharges()).hasSize(3);
        r.discharges().forEach(d -> {
            assertThat(d.setoffAmount().add(d.paymentAmount())).isEqualByComparingTo(d.convertedAmount());
            assertThat(d.paymentAmount()).isEqualByComparingTo("0");
            assertThat(d.setoffAmount()).isEqualByComparingTo("1000000.00");
        });
    }

    /** 非等额环：A 多收 25 万，最终只有 B -> A 一笔净付款，其余互抵。 */
    @Test
    void unequalRing_reducesToSingleNetPayment() {
        List<ClaimInput> claims = List.of(
                claim("1", "A", "B", "1000000.00"),
                claim("4", "A", "B", "250000.00"),
                claim("2", "B", "C", "1000000.00"),
                claim("3", "C", "A", "1000000.00"));

        GroupResult r = engine.computeGroup("NA", "CNY", false, null, claims);

        assertThat(r.positions().get("A")).isEqualByComparingTo("250000.00");
        assertThat(r.positions().get("B")).isEqualByComparingTo("-250000.00");
        assertThat(r.positions().get("C")).isEqualByComparingTo("0");
        assertThat(r.instructions()).hasSize(1);
        assertThat(r.instructions().get(0).type()).isEqualTo("NET_PAYMENT");
        assertThat(r.instructions().get(0).fromEntity()).isEqualTo("B");
        assertThat(r.instructions().get(0).toEntity()).isEqualTo("A");
        assertThat(r.instructions().get(0).amount()).isEqualByComparingTo("250000.00");
        assertConservation(r);
    }

    /** 无互抵协议时服务层不会把发票送进引擎；这里验证单张发票不被凭空抵销。 */
    @Test
    void isolatedClaim_remainsOneGrossPayment_noSetoff() {
        List<ClaimInput> claims = List.of(claim("1", "G", "H", "50000.00"));

        GroupResult r = engine.computeGroup(null, "HKD", false, null, claims);

        assertThat(r.instructions()).hasSize(1);
        assertThat(r.instructions().get(0).type()).isEqualTo("NET_PAYMENT");
        assertThat(r.instructions().get(0).amount()).isEqualByComparingTo("50000.00");
        // 债务人 H 自己没有应收发票，互抵额度为 0，全部走付款——原始债务被保留。
        assertThat(r.discharges().get(0).setoffAmount()).isEqualByComparingTo("0");
        assertThat(r.discharges().get(0).paymentAmount()).isEqualByComparingTo("50000.00");
    }

    /**
     * 跨币种：EUR 200.01 @1.085 = 217.01085 -> 取整 217.01，逐笔尾差 -0.00085 挂在 D；
     * 现金净头寸按取整后金额轧差保持零和；尾差 +0.00085 归集给承担方 F。
     */
    @Test
    void crossCurrency_roundingDoesNotBreakEntityBalances() {
        BigDecimal rate = bd("1.0850000000");
        List<ClaimInput> claims = List.of(
                fxClaim("x1", "D", "E", "EUR", "200.01", "USD", rate),
                fxClaim("x2", "E", "F", "USD", "300.00", "USD", BigDecimal.ONE),
                fxClaim("x3", "F", "D", "USD", "217.01", "USD", BigDecimal.ONE));

        GroupResult r = engine.computeGroup("NA-X", "USD", true, "F", claims);

        // 现金净头寸：D=0, E=+82.99, F=-82.99，合计 0（尾差没有混进现金头寸）。
        assertThat(r.positions().get("D")).isEqualByComparingTo("0");
        assertThat(r.positions().get("E")).isEqualByComparingTo("82.99");
        assertThat(r.positions().get("F")).isEqualByComparingTo("-82.99");
        BigDecimal sum = r.positions().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("0");

        // 唯一现金指令 F -> E 82.99。
        assertThat(r.instructions()).hasSize(1);
        assertThat(r.instructions().get(0).fromEntity()).isEqualTo("F");
        assertThat(r.instructions().get(0).toEntity()).isEqualTo("E");
        assertThat(r.instructions().get(0).amount()).isEqualByComparingTo("82.99");

        // 尾差归属零和：D 名下 -0.00085，F +0.00085。
        assertThat(r.fxResiduals().get("D")).isEqualByComparingTo("-0.000850");
        assertThat(r.bearerAdjust()).isEqualByComparingTo("0.000850");
        assertThat(r.bearerEntity()).isEqualTo("F");
        assertConservation(r);
    }

    @Test
    void crossCurrency_withoutBearer_throws() {
        BigDecimal rate = bd("1.085");
        List<ClaimInput> claims = List.of(
                fxClaim("x1", "D", "E", "EUR", "200.01", "USD", rate));
        assertThatThrownBy(() -> engine.computeGroup("NA-X", "USD", true, null, claims))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尾差承担方");
    }

    private static ClaimInput fxClaim(String id, String creditor, String debtor,
                                      String ccy, String amount, String clearingCcy,
                                      BigDecimal rate) {
        BigDecimal a = new BigDecimal(amount);
        BigDecimal raw = Currencies.convertRaw(a, rate);
        BigDecimal converted = ccy.equals(clearingCcy)
                ? Currencies.roundCash(ccy, a)
                : Currencies.convert(a, rate, clearingCcy);
        return new ClaimInput(id, "INV-" + id, creditor, debtor, ccy, a,
                converted, raw, rate);
    }

    /** 每个主体：毛应收 - 毛应付 = 现金净头寸；每张发票 setoff+payment=换算额。 */
    private void assertConservation(GroupResult r) {
        Map<String, BigDecimal> recv = new HashMap<>();
        Map<String, BigDecimal> pay = new HashMap<>();
        for (var d : r.discharges()) {
            recv.merge(d.creditor(), d.convertedAmount(), BigDecimal::add);
            pay.merge(d.debtor(), d.convertedAmount(), BigDecimal::add);
            assertThat(d.setoffAmount().add(d.paymentAmount()))
                    .isEqualByComparingTo(d.convertedAmount());
        }
        for (String entity : r.positions().keySet()) {
            BigDecimal expected = recv.getOrDefault(entity, BigDecimal.ZERO)
                    .subtract(pay.getOrDefault(entity, BigDecimal.ZERO));
            assertThat(r.positions().get(entity))
                    .as("主体 %s 净头寸边界", entity)
                    .isEqualByComparingTo(expected);
        }
    }
}
