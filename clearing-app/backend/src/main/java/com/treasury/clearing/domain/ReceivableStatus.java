package com.treasury.clearing.domain;

/** 债权状态：原始债权记录不可删除，只在清算批次确认后增加抵销/清偿痕迹。 */
public enum ReceivableStatus {
    ACTIVE,   // 未进入任何已确认批次
    CLEARED,  // 已被某个已确认批次全部清偿
    VOID      // 原始作废（数据修正），不参与任何计算
}
