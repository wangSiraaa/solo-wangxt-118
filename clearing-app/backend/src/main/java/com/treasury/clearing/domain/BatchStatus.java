package com.treasury.clearing.domain;

public enum BatchStatus {
    /** 试算：仅保存方案，不影响任何原始债权。 */
    SIMULATED,
    /** 确认：方案生效，产生互抵与付款指令（指令不连接真实银行）。 */
    CONFIRMED,
    /** 已确认批次的撤销申请审批中：此态下不可再撤销、也不可重复提交。 */
    REVERSAL_PENDING,
    /** 已冲正：原确认方案保留可审计，对应债权已由冲正批次恢复。终态，不可逆。 */
    REVERSED
}
