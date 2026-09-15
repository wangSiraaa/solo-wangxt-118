package com.treasury.clearing.domain;

/** 批次业务性质：普通清算批次，或用于恢复已确认清偿的冲正批次。 */
public enum BatchKind {
    /** 常规试算/确认清算批次。 */
    NETTING,
    /** 冲正批次：冲回某条已确认批次，恢复其实际清偿的债权。本身不可再撤销。 */
    REVERSAL
}
