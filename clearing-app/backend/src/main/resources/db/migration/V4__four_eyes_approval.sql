-- 分级四眼审批：协议门槛 + 撤销申请名额快照 + 不可变审批决议链

-- 1) 互抵协议的一审/双审门槛（按该协议清算币种总清偿额比较；为空=始终一审）
ALTER TABLE netting_agreement
    ADD COLUMN dual_approval_threshold NUMERIC(20,6);

-- 演示门槛：同币种大额协议双审；跨币种演示协议门槛很高（该演示数据清偿额远低于门槛）保持一审
UPDATE netting_agreement SET dual_approval_threshold = 100000.000000 WHERE code = 'NA-MULTI';
UPDATE netting_agreement SET dual_approval_threshold = 1000000.000000 WHERE code = 'NA-XCCY';

-- 2) 撤销申请：所需/已有名额 + 门槛快照 + 最终操作人
ALTER TABLE reversal_request
    ADD COLUMN required_approvals      INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN approvals_received      INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN threshold_agreement     VARCHAR(32),
    ADD COLUMN threshold_amount        NUMERIC(20,6),
    ADD COLUMN gross_cleared_amount    NUMERIC(20,6),
    ADD COLUMN gross_cleared_currency  VARCHAR(3),
    ADD COLUMN finalized_by            VARCHAR(64);

-- 旧数据若有已处理申请，把已知名额补齐为已完成（迁移兼容）
UPDATE reversal_request
SET required_approvals = 1, approvals_received =
        CASE WHEN status = 'PROCESSED' THEN 1 ELSE 0 END
WHERE required_approvals = 1;

-- 状态扩展 PARTIALLY_APPROVED（19 字符，需把 V3 的 VARCHAR(16) 加宽）
ALTER TABLE reversal_request
    ALTER COLUMN status TYPE VARCHAR(20);
ALTER TABLE reversal_request DROP CONSTRAINT IF EXISTS reversal_request_status_check;
ALTER TABLE reversal_request
    ADD CONSTRAINT reversal_request_status_check
    CHECK (status IN ('REQUESTED', 'PARTIALLY_APPROVED', 'PROCESSED', 'REJECTED'));

-- 3) 不可变审批决议（只增不改不删）
CREATE TABLE reversal_decision (
    id                 VARCHAR(48) PRIMARY KEY,
    version            BIGINT NOT NULL DEFAULT 0,
    request_id         VARCHAR(40) NOT NULL REFERENCES reversal_request(id),
    seq                INTEGER NOT NULL,
    outcome            VARCHAR(10) NOT NULL CHECK (outcome IN ('APPROVE', 'REJECT')),
    approver           VARCHAR(64) NOT NULL,
    comment            VARCHAR(512),
    decided_at         TIMESTAMPTZ NOT NULL,
    status_before      VARCHAR(20) NOT NULL,
    status_after       VARCHAR(20) NOT NULL,
    reversal_batch_id  VARCHAR(40)
);
CREATE INDEX ix_decision_request ON reversal_decision(request_id);
-- 同一撤销申请下同一审批人只能有一条决议（自审由服务层拦，重复/并发由该唯一索引兜底）
CREATE UNIQUE INDEX uq_decision_request_approver
    ON reversal_decision(request_id, approver);
