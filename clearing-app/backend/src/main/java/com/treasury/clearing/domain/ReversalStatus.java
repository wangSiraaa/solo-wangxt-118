package com.treasury.clearing.domain;

/** 撤销申请状态机。 */
public enum ReversalStatus {
    /** 已申请，待审批。 */
    REQUESTED,
    /** 审批通过且冲正批次已生成、债权已恢复（终态）。 */
    PROCESSED,
    /** 审批驳回，原确认批次恢复为 CONFIRMED（终态）。 */
    REJECTED
}
