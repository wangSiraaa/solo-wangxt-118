package com.treasury.clearing.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * 币种金额精度规则。尾差只能发生在把高精度换算结果截断到币种最小记账单位时；
 * 绝大多数清算币种是 2 位小数，JPY/KRW 等为 0 位。
 */
public final class Currencies {

    private static final Set<String> ZERO_SCALE = Set.of("JPY", "KRW", "VND", "CLP", "ISK");
    private static final Set<String> THREE_SCALE = Set.of("KWD", "BHD", "OMR", "TND");

    /** 内部台账统一保留 6 位小数存储，但清算指令金额按币种记账精度取整。 */
    public static final int STORAGE_SCALE = 6;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private Currencies() {
    }

    public static int cashScale(String currency) {
        if (ZERO_SCALE.contains(currency)) {
            return 0;
        }
        if (THREE_SCALE.contains(currency)) {
            return 3;
        }
        return 2;
    }

    public static BigDecimal roundCash(String currency, BigDecimal value) {
        return value.setScale(cashScale(currency), ROUNDING);
    }

    /** 折算：amount * rate，先保留 6 位中间精度，再按目标币种记账精度取整。 */
    public static BigDecimal convert(BigDecimal amount, BigDecimal rate, String toCurrency) {
        return amount.multiply(rate)
                .setScale(STORAGE_SCALE, ROUNDING)
                .setScale(cashScale(toCurrency), ROUNDING);
    }

    /** 未取整的折算值，用于量化尾差。 */
    public static BigDecimal convertRaw(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate);
    }
}
