-- 撤销与冲正：批次状态机扩展 + 撤销申请审计表
-- 原确认批次的任何金额/清偿明细都不改动，冲正通过新增一条 kind=REVERSAL 的批次表达。

-- 乐观锁版本列
ALTER TABLE clearing_batch ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE receivable     ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- 批次性质与冲正双向关联
ALTER TABLE clearing_batch ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'NETTING';
ALTER TABLE clearing_batch ADD COLUMN reverses_batch_id VARCHAR(40);
ALTER TABLE clearing_batch ADD COLUMN reversal_batch_id VARCHAR(40);
ALTER TABLE clearing_batch ADD COLUMN reversed_at TIMESTAMPTZ;
ALTER TABLE clearing_batch ADD COLUMN reversal_requested_at TIMESTAMPTZ;

-- 状态枚举扩展：REVERSAL_PENDING / REVERSED
ALTER TABLE clearing_batch DROP CONSTRAINT IF EXISTS clearing_batch_status_check;
ALTER TABLE clearing_batch
    ADD CONSTRAINT clearing_batch_status_check
    CHECK (status IN ('SIMULATED', 'CONFIRMED', 'REVERSAL_PENDING', 'REVERSED'));
ALTER TABLE clearing_batch
    ADD CONSTRAINT ck_batch_kind CHECK (kind IN ('NETTING', 'REVERSAL'));
-- 冲正批次必须指向一条原批次
ALTER TABLE clearing_batch
    ADD CONSTRAINT fk_batch_reverses FOREIGN KEY (reverses_batch_id)
    REFERENCES clearing_batch(id);

CREATE INDEX ix_batch_reverses ON clearing_batch(reverses_batch_id);

-- 撤销申请（不可变审计）
CREATE TABLE reversal_request (
    id                 VARCHAR(40) PRIMARY KEY,
    version            BIGINT NOT NULL DEFAULT 0,
    original_batch_id  VARCHAR(40) NOT NULL REFERENCES clearing_batch(id),
    reversal_batch_id  VARCHAR(40) REFERENCES clearing_batch(id),
    status             VARCHAR(16) NOT NULL
        CHECK (status IN ('REQUESTED', 'PROCESSED', 'REJECTED')),
    reason             VARCHAR(512),
    requested_by       VARCHAR(64),
    requested_at       TIMESTAMPTZ NOT NULL,
    approved_by        VARCHAR(64),
    approved_at        TIMESTAMPTZ,
    processed_at       TIMESTAMPTZ,
    restored_count     INTEGER,
    rejected_by        VARCHAR(64),
    rejected_at        TIMESTAMPTZ,
    reject_reason      VARCHAR(512)
);

-- 同一确认批次最多一条撤销申请（重复提交/并发的数据库层兜底）
CREATE UNIQUE INDEX uq_reversal_original_batch ON reversal_request(original_batch_id);
