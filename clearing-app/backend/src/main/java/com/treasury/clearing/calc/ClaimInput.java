package com.treasury.clearing.calc;

import java.math.BigDecimal;

/**
 * 参与清算的单张合格债权（发票）。引擎不接触 JPA 实体，便于纯单测。
 *
 * @param convertedAmount 已折算到组清算币种的记账金额（同币种=原始金额）
 * @param fxRate          使用的汇率（同币种=1）
 * @param rawConverted    未按币种精度取整的折算值，用于计算逐笔尾差
 */
public record ClaimInput(String id,
                         String invoiceNo,
                         String creditor,
                         String debtor,
                         String currency,
                         BigDecimal amount,
                         BigDecimal convertedAmount,
                         BigDecimal rawConverted,
                         BigDecimal fxRate) {
}
