package com.treasury.clearing.domain;

/**
 * 差额更正申请状态机（分级四眼审批）。
 * REQUESTED → PARTIALLY_APPROVED → PROCESSED；任一决议驳回即 REJECTED（终态）。
 */
public enum AdjustmentStatus {
    /** 已申请，尚无决议。 */
    REQUESTED,
    /** 双审门槛下首审通过，等待第二审批人。 */
    PARTIALLY_APPROVED,
    /** 名额凑满：唯一差额批次已生成（终态）。 */
    PROCESSED,
    /** 某审批人驳回（终态），原批次保持 CONFIRMED。 */
    REJECTED
}
