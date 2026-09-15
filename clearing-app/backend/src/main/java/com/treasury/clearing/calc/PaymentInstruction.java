package com.treasury.clearing.calc;

import java.math.BigDecimal;

/** 轧差后实际需要执行的一笔指令。amount=0 的 SETOFF 表示纯账面对冲（环形债务闭环）。 */
public record PaymentInstruction(String type,        // SETOFF / NET_PAYMENT
                                 String fromEntity,
                                 String toEntity,
                                 BigDecimal amount,  // 清算币种
                                 String description) {
}
