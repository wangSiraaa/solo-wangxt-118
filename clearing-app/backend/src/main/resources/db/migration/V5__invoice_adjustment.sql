-- 确认方案差额更正：批次性质扩展 + 更正申请/发票更正事件/更正决议

-- 1) 批次：ADJUSTMENT 性质与双向关联
ALTER TABLE clearing_batch ADD COLUMN adjusts_batch_id VARCHAR(40);
ALTER TABLE clearing_batch ADD COLUMN adjustment_batch_id VARCHAR(40);

ALTER TABLE clearing_batch DROP CONSTRAINT IF EXISTS ck_batch_kind;
ALTER TABLE clearing_batch
    ADD CONSTRAINT ck_batch_kind CHECK (kind IN ('NETTING', 'REVERSAL', 'ADJUSTMENT'));

ALTER TABLE clearing_batch DROP CONSTRAINT IF EXISTS clearing_batch_status_check;
ALTER TABLE clearing_batch
    ALTER COLUMN status TYPE VARCHAR(20);
ALTER TABLE clearing_batch
    ADD CONSTRAINT clearing_batch_status_check CHECK (
        status IN ('SIMULATED', 'CONFIRMED', 'REVERSAL_PENDING', 'REVERSED', 'ADJUSTMENT_PENDING'));

ALTER TABLE clearing_batch
    ADD CONSTRAINT fk_batch_adjusts FOREIGN KEY (adjusts_batch_id)
    REFERENCES clearing_batch(id);
CREATE INDEX ix_batch_adjusts ON clearing_batch(adjusts_batch_id);

-- 2) 差额更正申请
CREATE TABLE adjustment_request (
    id                     VARCHAR(40) PRIMARY KEY,
    version                BIGINT NOT NULL DEFAULT 0,
    original_batch_id      VARCHAR(40) NOT NULL REFERENCES clearing_batch(id),
    adjustment_batch_id    VARCHAR(40) REFERENCES clearing_batch(id),
    status                 VARCHAR(20) NOT NULL
        CHECK (status IN ('REQUESTED', 'PARTIALLY_APPROVED', 'PROCESSED', 'REJECTED')),
    required_approvals     INTEGER NOT NULL,
    approvals_received     INTEGER NOT NULL,
    threshold_agreement    VARCHAR(32),
    threshold_amount       NUMERIC(20,6),
    delta_gross_amount     NUMERIC(20,6),
    delta_gross_currency   VARCHAR(3),
    reason                 VARCHAR(512),
    requested_by           VARCHAR(64),
    requested_at           TIMESTAMPTZ NOT NULL,
    finalized_by           VARCHAR(64),
    processed_at           TIMESTAMPTZ,
    event_count            INTEGER,
    rejected_by            VARCHAR(64),
    rejected_at            TIMESTAMPTZ,
    reject_reason          VARCHAR(512)
);
-- 一条原确认批次最多一条更正申请（重复提交/并发的 DB 兜底）
CREATE UNIQUE INDEX uq_adjustment_original_batch ON adjustment_request(original_batch_id);

-- 3) 不可修改的发票更正事件
CREATE TABLE invoice_correction_event (
    id                 VARCHAR(48) PRIMARY KEY,
    version            BIGINT NOT NULL DEFAULT 0,
    request_id         VARCHAR(40) NOT NULL REFERENCES adjustment_request(id),
    seq                INTEGER NOT NULL,
    receivable_id      VARCHAR(40) NOT NULL,
    invoice_no         VARCHAR(40) NOT NULL,
    corrected_field    VARCHAR(10) NOT NULL CHECK (corrected_field IN ('AMOUNT', 'AGREEMENT')),
    old_amount         NUMERIC(20,6) NOT NULL,
    old_currency       VARCHAR(3) NOT NULL,
    old_agreement_code VARCHAR(32),
    new_amount         NUMERIC(20,6) NOT NULL,
    new_currency       VARCHAR(3) NOT NULL,
    new_agreement_code VARCHAR(32),
    effective_scope    VARCHAR(256),
    reason             VARCHAR(512),
    requested_by       VARCHAR(64),
    created_at         TIMESTAMPTZ NOT NULL,
    old_converted      NUMERIC(20,6) NOT NULL,
    new_converted      NUMERIC(20,6) NOT NULL,
    delta_converted    NUMERIC(20,6) NOT NULL,
    clearing_currency  VARCHAR(3) NOT NULL
);
CREATE INDEX ix_corr_request ON invoice_correction_event(request_id);
-- 同一更正申请内同一发票唯一（杜绝同一发票重复更正）
CREATE UNIQUE INDEX uq_corr_request_receivable
    ON invoice_correction_event(request_id, receivable_id);

-- 4) 不可修改的更正审批决议
CREATE TABLE adjustment_decision (
    id                   VARCHAR(48) PRIMARY KEY,
    version              BIGINT NOT NULL DEFAULT 0,
    request_id           VARCHAR(40) NOT NULL REFERENCES adjustment_request(id),
    seq                  INTEGER NOT NULL,
    outcome              VARCHAR(10) NOT NULL CHECK (outcome IN ('APPROVE', 'REJECT')),
    approver             VARCHAR(64) NOT NULL,
    comment              VARCHAR(512),
    decided_at           TIMESTAMPTZ NOT NULL,
    status_before        VARCHAR(20) NOT NULL,
    status_after         VARCHAR(20) NOT NULL,
    adjustment_batch_id  VARCHAR(40)
);
CREATE INDEX ix_adj_decision_request ON adjustment_decision(request_id);
-- 同一更正申请下同一审批人唯一
CREATE UNIQUE INDEX uq_adj_decision_request_approver
    ON adjustment_decision(request_id, approver);
