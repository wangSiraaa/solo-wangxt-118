package com.treasury.clearing.domain;

public enum PaymentType {
    /** 组内互抵（账面对冲，无资金移动）。 */
    SETOFF,
    /** 净额模拟付款指令（仅台账记录，不接真实银行）。 */
    NET_PAYMENT,
    /** 跨币种尾差归属调整（仅记账金额，非真实收付）。 */
    ROUNDING,
    /** 发票差额更正指令（只表达净额增减，不清偿/恢复任何原始债权，不接银行）。 */
    ADJUSTMENT
}
