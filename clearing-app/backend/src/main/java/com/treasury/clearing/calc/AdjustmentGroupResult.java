package com.treasury.clearing.calc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 单个受影响清算组（协议 + 清算币种）的差额计算结果。
 * deltaNet 合计严格为 0（每张发票的债务双方变化相反）。
 */
public record AdjustmentGroupResult(String agreementCode,
                                    String clearingCurrency,
                                    Map<String, BigDecimal> deltaNet,
                                    List<AdjustmentInstruction> instructions) {
}
