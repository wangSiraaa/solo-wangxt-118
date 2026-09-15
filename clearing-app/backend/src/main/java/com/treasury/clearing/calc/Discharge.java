package com.treasury.clearing.calc;

import java.math.BigDecimal;

/**
 * 一张发票的清偿分配（清算币种）。
 * setoff + payment 恒等于 convertedAmount。
 */
public record Discharge(String receivableId,
                        String invoiceNo,
                        String creditor,
                        String debtor,
                        String originalCurrency,
                        BigDecimal originalAmount,
                        BigDecimal convertedAmount,
                        BigDecimal setoffAmount,
                        BigDecimal paymentAmount,
                        BigDecimal fxRate) {
}
