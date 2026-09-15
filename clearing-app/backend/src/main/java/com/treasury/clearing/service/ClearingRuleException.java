package com.treasury.clearing.service;

/** 业务规则校验失败（422 语义）。 */
public class ClearingRuleException extends RuntimeException {
    public ClearingRuleException(String message) {
        super(message);
    }
}
