package com.treasury.clearing.domain;

public enum BatchStatus {
    /** 试算：仅保存方案，不影响任何原始债权。 */
    SIMULATED,
    /** 确认：方案生效，产生互抵与付款指令（指令不连接真实银行）。 */
    CONFIRMED
}
