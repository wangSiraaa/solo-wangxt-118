-- 内部债务清算试算：清算批次与原始债权的 PostgreSQL 结构
-- 设计原则：原始债权(receivable)是事实表，清算只新增批次侧数据，不删除/改写金额。

CREATE TABLE legal_entity (
    code        VARCHAR(32) PRIMARY KEY,
    name        VARCHAR(128) NOT NULL
);

CREATE TABLE netting_agreement (
    code                 VARCHAR(32) PRIMARY KEY,
    name                 VARCHAR(128) NOT NULL,
    cross_currency       BOOLEAN NOT NULL DEFAULT FALSE,
    settlement_currency  VARCHAR(3),
    rounding_party       VARCHAR(32)
);

CREATE TABLE agreement_party (
    id              VARCHAR(64) PRIMARY KEY,
    agreement_code  VARCHAR(32) NOT NULL REFERENCES netting_agreement(code),
    entity_code     VARCHAR(32) NOT NULL REFERENCES legal_entity(code),
    UNIQUE (agreement_code, entity_code)
);

CREATE TABLE fx_rate (
    id             VARCHAR(64) PRIMARY KEY,
    from_currency  VARCHAR(3) NOT NULL,
    to_currency    VARCHAR(3) NOT NULL,
    rate           NUMERIC(20,10) NOT NULL,
    rate_time      TIMESTAMPTZ NOT NULL,
    source         VARCHAR(64),
    CHECK (rate > 0)
);
CREATE INDEX ix_fx_pair_time ON fx_rate(from_currency, to_currency, rate_time DESC);

CREATE TABLE receivable (
    id              VARCHAR(40) PRIMARY KEY,
    invoice_no      VARCHAR(40) NOT NULL,
    creditor_code   VARCHAR(32) NOT NULL REFERENCES legal_entity(code),
    debtor_code     VARCHAR(32) NOT NULL REFERENCES legal_entity(code),
    currency        VARCHAR(3) NOT NULL,
    amount          NUMERIC(20,6) NOT NULL,
    invoice_date    DATE NOT NULL,
    agreement_code  VARCHAR(32) REFERENCES netting_agreement(code),
    pledged         BOOLEAN NOT NULL DEFAULT FALSE,
    disputed        BOOLEAN NOT NULL DEFAULT FALSE,
    status          VARCHAR(16) NOT NULL,
    CHECK (amount > 0),
    CHECK (creditor_code <> debtor_code)
);
CREATE INDEX ix_receivable_status ON receivable(status);

CREATE TABLE clearing_batch (
    id                      VARCHAR(40) PRIMARY KEY,
    label                   VARCHAR(128) NOT NULL,
    status                  VARCHAR(16) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL,
    valuation_time          TIMESTAMPTZ NOT NULL,
    confirmed_at            TIMESTAMPTZ,
    original_claim_count    INTEGER NOT NULL,
    resulting_entry_count   INTEGER NOT NULL,
    excluded_count          INTEGER NOT NULL,
    created_by              VARCHAR(64)
);

CREATE TABLE clearing_group (
    id                     VARCHAR(48) PRIMARY KEY,
    batch_id               VARCHAR(40) NOT NULL REFERENCES clearing_batch(id) ON DELETE CASCADE,
    agreement_code         VARCHAR(32) NOT NULL,
    clearing_currency      VARCHAR(3) NOT NULL,
    cross_currency         BOOLEAN NOT NULL,
    claim_count            INTEGER NOT NULL,
    gross_claims_display   VARCHAR(512),
    net_entry_count        INTEGER NOT NULL
);
CREATE INDEX ix_group_batch ON clearing_group(batch_id);

CREATE TABLE net_position (
    id               VARCHAR(64) PRIMARY KEY,
    group_id         VARCHAR(48) NOT NULL REFERENCES clearing_group(id) ON DELETE CASCADE,
    entity_code      VARCHAR(32) NOT NULL,
    gross_receivable NUMERIC(20,6) NOT NULL,
    gross_payable    NUMERIC(20,6) NOT NULL,
    net_amount       NUMERIC(20,6) NOT NULL
);

CREATE TABLE clearing_entry (
    id           VARCHAR(64) PRIMARY KEY,
    group_id     VARCHAR(48) NOT NULL REFERENCES clearing_group(id) ON DELETE CASCADE,
    type         VARCHAR(16) NOT NULL,
    from_entity  VARCHAR(32) NOT NULL,
    to_entity    VARCHAR(32) NOT NULL,
    amount       NUMERIC(20,6) NOT NULL,
    currency     VARCHAR(3) NOT NULL,
    description  VARCHAR(512)
);

CREATE TABLE invoice_discharge (
    id                VARCHAR(64) PRIMARY KEY,
    group_id          VARCHAR(48) NOT NULL REFERENCES clearing_group(id) ON DELETE CASCADE,
    receivable_id     VARCHAR(40) NOT NULL,
    invoice_no        VARCHAR(40) NOT NULL,
    creditor_code     VARCHAR(32) NOT NULL,
    debtor_code       VARCHAR(32) NOT NULL,
    original_currency VARCHAR(3) NOT NULL,
    original_amount   NUMERIC(20,6) NOT NULL,
    converted_amount  NUMERIC(20,6) NOT NULL,
    setoff_amount     NUMERIC(20,6) NOT NULL,
    payment_amount    NUMERIC(20,6) NOT NULL,
    fx_rate           NUMERIC(20,10) NOT NULL,
    CHECK (setoff_amount >= 0),
    CHECK (payment_amount >= 0)
);
CREATE INDEX ix_discharge_receivable ON invoice_discharge(receivable_id);

CREATE TABLE rounding_line (
    id             VARCHAR(64) PRIMARY KEY,
    group_id       VARCHAR(48) NOT NULL REFERENCES clearing_group(id) ON DELETE CASCADE,
    line_type      VARCHAR(20) NOT NULL,
    entity_code    VARCHAR(32) NOT NULL,
    currency       VARCHAR(3) NOT NULL,
    amount         NUMERIC(20,6) NOT NULL,
    ref_invoice_no VARCHAR(40),
    fx_rate        NUMERIC(20,10),
    rate_time      VARCHAR(40),
    note           VARCHAR(256)
);

CREATE TABLE excluded_claim (
    id               VARCHAR(64) PRIMARY KEY,
    batch_id         VARCHAR(40) NOT NULL REFERENCES clearing_batch(id) ON DELETE CASCADE,
    receivable_id    VARCHAR(40) NOT NULL,
    invoice_no       VARCHAR(40) NOT NULL,
    exclusion_reason VARCHAR(32) NOT NULL,
    detail           VARCHAR(256)
);
CREATE INDEX ix_excluded_batch ON excluded_claim(batch_id);
