-- 日终关账与合规再开账：不可修改报表快照 + 版本化 + 四眼再开账

-- 0) 撤销/更正申请改为“仅活动态唯一”：驳回后允许重新申请
DROP INDEX IF EXISTS uq_reversal_original_batch;
CREATE UNIQUE INDEX uq_reversal_original_batch ON reversal_request(original_batch_id)
    WHERE status IN ('REQUESTED', 'PARTIALLY_APPROVED');

-- 1) 关账报表头
CREATE TABLE closing_report (
    id                  VARCHAR(40) PRIMARY KEY,
    version             BIGINT NOT NULL DEFAULT 0,
    settlement_date     DATE NOT NULL,
    report_version      INTEGER NOT NULL,
    status              VARCHAR(20) NOT NULL
        CHECK (status IN ('CLOSED', 'REOPEN_PENDING', 'SUPERSEDED')),
    closed_by           VARCHAR(64),
    closed_at           TIMESTAMPTZ NOT NULL,
    reopen_reason       VARCHAR(512),
    created_by_reopen   VARCHAR(64),
    batch_count         INTEGER NOT NULL,
    total_cash_amount   NUMERIC(20,6)
);
-- 同一结算日同一版本唯一（并发关账/再开账兜底）
CREATE UNIQUE INDEX uq_closing_date_version ON closing_report(settlement_date, report_version);
-- 任意时刻最多一个未终结（CLOSED/REOPEN_PENDING）报表：部分唯一索引
CREATE UNIQUE INDEX uq_closing_active ON closing_report(settlement_date)
    WHERE status IN ('CLOSED', 'REOPEN_PENDING');
CREATE INDEX ix_closing_date ON closing_report(settlement_date);

-- 2) 汇总行：协议 + 币种 + 法人
CREATE TABLE closing_report_line (
    id                VARCHAR(48) PRIMARY KEY,
    report_id         VARCHAR(40) NOT NULL REFERENCES closing_report(id),
    agreement_code    VARCHAR(32) NOT NULL,
    clearing_currency VARCHAR(3) NOT NULL,
    entity_code       VARCHAR(32) NOT NULL,
    net_position      NUMERIC(20,6) NOT NULL,
    gross_receivable  NUMERIC(20,6) NOT NULL,
    gross_payable     NUMERIC(20,6) NOT NULL,
    cash_amount       NUMERIC(20,6) NOT NULL,
    discharge_count   INTEGER NOT NULL
);
CREATE INDEX ix_closing_line_report ON closing_report_line(report_id);

-- 3) 批次贡献链：每个被纳入批次的每个组一行
CREATE TABLE closing_contribution (
    id                VARCHAR(56) PRIMARY KEY,
    report_id         VARCHAR(40) NOT NULL REFERENCES closing_report(id),
    batch_id          VARCHAR(40) NOT NULL,
    batch_kind        VARCHAR(16) NOT NULL,
    batch_label       VARCHAR(128),
    agreement_code    VARCHAR(32) NOT NULL,
    clearing_currency VARCHAR(3) NOT NULL,
    gross_receivable  NUMERIC(20,6) NOT NULL,
    gross_payable     NUMERIC(20,6) NOT NULL,
    net_position      NUMERIC(20,6) NOT NULL,
    cash_amount       NUMERIC(20,6) NOT NULL,
    discharge_count   INTEGER NOT NULL,
    contribution_type VARCHAR(16) NOT NULL
);
CREATE INDEX ix_closing_contrib_report ON closing_contribution(report_id);
CREATE INDEX ix_closing_contrib_batch ON closing_contribution(batch_id);

-- 4) 再开账申请（不可变审计）
CREATE TABLE reopen_request (
    id                    VARCHAR(40) PRIMARY KEY,
    version               BIGINT NOT NULL DEFAULT 0,
    settlement_date       DATE NOT NULL,
    from_report_version   INTEGER NOT NULL,
    new_report_version    INTEGER,
    new_report_id         VARCHAR(40),
    status                VARCHAR(20) NOT NULL
        CHECK (status IN ('REQUESTED', 'PARTIALLY_APPROVED', 'PROCESSED', 'REJECTED')),
    required_approvals    INTEGER NOT NULL,
    approvals_received    INTEGER NOT NULL,
    threshold_agreement   VARCHAR(32),
    threshold_amount      NUMERIC(20,6),
    day_cash_amount       NUMERIC(20,6),
    day_cash_currency     VARCHAR(3),
    reason                VARCHAR(512),
    requested_by          VARCHAR(64),
    requested_at          TIMESTAMPTZ NOT NULL,
    finalized_by          VARCHAR(64),
    processed_at          TIMESTAMPTZ,
    affected_batch_count  INTEGER,
    rejected_by           VARCHAR(64),
    rejected_at           TIMESTAMPTZ,
    reject_reason         VARCHAR(512)
);
-- 同一结算日最多一条未终结再开账申请（部分唯一索引）
CREATE UNIQUE INDEX uq_reopen_active_date ON reopen_request(settlement_date)
    WHERE status IN ('REQUESTED', 'PARTIALLY_APPROVED');
CREATE INDEX ix_reopen_date ON reopen_request(settlement_date);

-- 5) 再开账决议（不可变，四眼）
CREATE TABLE reopen_decision (
    id                     VARCHAR(48) PRIMARY KEY,
    version                BIGINT NOT NULL DEFAULT 0,
    request_id             VARCHAR(40) NOT NULL REFERENCES reopen_request(id),
    seq                    INTEGER NOT NULL,
    outcome                VARCHAR(10) NOT NULL CHECK (outcome IN ('APPROVE', 'REJECT')),
    approver               VARCHAR(64) NOT NULL,
    comment                VARCHAR(512),
    decided_at             TIMESTAMPTZ NOT NULL,
    status_before          VARCHAR(20) NOT NULL,
    status_after           VARCHAR(20) NOT NULL,
    closing_status_before  VARCHAR(20),
    closing_status_after   VARCHAR(20),
    new_report_id          VARCHAR(40)
);
CREATE INDEX ix_reopen_decision_request ON reopen_decision(request_id);
CREATE UNIQUE INDEX uq_reopen_decision_approver ON reopen_decision(request_id, approver);
