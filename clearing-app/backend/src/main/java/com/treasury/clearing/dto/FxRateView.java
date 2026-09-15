package com.treasury.clearing.dto;

import java.math.BigDecimal;

public record FxRateView(String id,
                         String fromCurrency,
                         String toCurrency,
                         BigDecimal rate,
                         String rateTime,
                         String source) {
}
