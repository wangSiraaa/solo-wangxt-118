package com.treasury.clearing.domain;

/** 批次业务性质：常规清算、冲正批次，或发票差额更正批次。 */
public enum BatchKind {
    /** 常规试算/确认清算批次。 */
    NETTING,
    /** 冲正批次：冲回某条已确认批次，恢复其实际清偿的债权。本身不可再撤销/更正。 */
    REVERSAL,
    /** 差额更正批次：仅承载个别发票更正后的净额差异与差额指令。本身不可再撤销/更正。 */
    ADJUSTMENT
}
