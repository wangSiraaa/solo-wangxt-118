package com.treasury.clearing.domain;

/** 再开账申请状态机（四眼审批）。 */
public enum ReopenStatus {
    REQUESTED,
    PARTIALLY_APPROVED,
    PROCESSED,
    REJECTED
}
