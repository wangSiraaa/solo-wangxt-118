package com.treasury.clearing.calc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 单个清算组（协议 + 清算币种）的计算结果。
 *
 * @param positions    各法人净头寸（应收 - 应付），合计严格为 0
 * @param instructions 轧差后的指令，笔数远小于发票笔数
 * @param discharges   逐发票的互抵/付款分配，供债务图追溯
 * @param fxResiduals  逐发票换算尾差，key=债权人（清算币种）
 * @param bearerAdjust 归集到尾差承担方的平衡金额（= -尾差合计）
 * @param grossByCcy   各原始币种的发票总额（展示用）
 */
public record GroupResult(String agreementCode,
                          String clearingCurrency,
                          boolean crossCurrency,
                          Map<String, BigDecimal> positions,
                          Map<String, BigDecimal> grossReceivable,
                          Map<String, BigDecimal> grossPayable,
                          List<PaymentInstruction> instructions,
                          List<Discharge> discharges,
                          Map<String, BigDecimal> fxResiduals,
                          BigDecimal bearerAdjust,
                          String bearerEntity,
                          Map<String, BigDecimal> grossByCcy,
                          int claimCount) {
}
