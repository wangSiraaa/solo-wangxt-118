package com.treasury.clearing.calc;

import java.math.BigDecimal;

/**
 * 单张发票的更正输入（已按目标清算币种、原估值时点完成折算）。
 *
 * @param oldConverted 原确认批次中该发票的清算币种金额
 * @param newConverted 更正后的清算币种金额
 * @param delta        新值 - 旧值（债权人的净变化，可正可负）
 */
public record CorrectionInput(String receivableId,
                              String invoiceNo,
                              String creditor,
                              String debtor,
                              String agreementCode,
                              String clearingCurrency,
                              String originalCurrency,
                              BigDecimal oldAmount,
                              BigDecimal newAmount,
                              BigDecimal oldConverted,
                              BigDecimal newConverted,
                              BigDecimal delta) {
}
