package com.treasury.clearing.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** 手工维护内部记账汇率（不接外部银行/行情）。 */
public record FxRateUpsertRequest(String fromCurrency,
                                  String toCurrency,
                                  BigDecimal rate,
                                  Instant rateTime,
                                  String source) {
}
