package com.treasury.clearing.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 试算请求。
 *
 * @param valuationTime 汇率/头寸时点（缺省取服务器当前时间）
 * @param createdBy     操作的资金人员
 */
public record TrialRequest(String label,
                           Instant valuationTime,
                           String createdBy) {
}
