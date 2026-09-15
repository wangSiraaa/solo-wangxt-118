package com.treasury.clearing.domain;

/** 日终结算报表（关账快照）状态。 */
public enum ClosingStatus {
    /** 有效关账：该结算日已锁定。 */
    CLOSED,
    /** 再开账申请审批中：该日仍锁定，审批通过后被新版本取代。 */
    REOPEN_PENDING,
    /** 已被更新版本取代（再开账完成），历史快照永久保留只读。 */
    SUPERSEDED
}
