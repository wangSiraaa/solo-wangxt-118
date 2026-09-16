-- 结算日统一互斥边界：关账/再开账/确认生效/撤销/更正末审按日串行
CREATE TABLE IF NOT EXISTS settlement_day_lock (
    settlement_date DATE PRIMARY KEY,
    version         BIGINT NOT NULL DEFAULT 0
);
