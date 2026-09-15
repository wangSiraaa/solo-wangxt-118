package com.treasury.clearing.calc;

import java.math.BigDecimal;

/** 一条差额指令：from→to 付款 amount（可正）；amount=0 表示纯账面无差异。 */
public record AdjustmentInstruction(String fromEntity,
                                    String toEntity,
                                    BigDecimal amount,
                                    String currency,
                                    String description) {
}
