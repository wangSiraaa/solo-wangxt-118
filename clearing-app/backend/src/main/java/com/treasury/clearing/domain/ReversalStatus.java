package com.treasury.clearing.domain;

/**
 * 撤销申请状态机（分级四眼审批）。
 * REQUESTED → (PARTIALLY_APPROVED) → PROCESSED；任一决议驳回即 REJECTED（终态）。
 */
public enum ReversalStatus {
    /** 已申请，尚无决议。 */
    REQUESTED,
    /** 双审门槛下已有一审决议，等待第二审批人。 */
    PARTIALLY_APPROVED,
    /** 名额凑满：冲正批次已生成、债权已恢复（终态）。 */
    PROCESSED,
    /** 某审批人驳回：原批次回到 CONFIRMED（终态）。 */
    REJECTED
}
