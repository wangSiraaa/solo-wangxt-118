package com.treasury.clearing.domain;

/** 状态冲突（409）：重复撤销、审批/确认与撤销并发等。请求方应安全重试或刷新后再操作。 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
