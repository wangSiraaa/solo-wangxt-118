-- 演示主数据与债权（覆盖验收场景）
-- 1) A/B/C 三方等额环形债务，同币种且有互抵协议 -> 0 笔资金收付
-- 2) D/E/F 跨币种环形债务 -> 展示逐项汇率、时点与尾差归属（F 承担）
-- 3) A/B 之间一张质押发票、一张争议发票 -> 必须排除，原债务保留
-- 4) G/H 互欠但无互抵协议 -> 不互抵，保留两笔原债务

INSERT INTO legal_entity(code, name) VALUES
 ('A','甲制造有限公司'),
 ('B','乙贸易有限公司'),
 ('C','丙物流有限公司'),
 ('D','丁欧洲采购 GmbH'),
 ('E','戊美洲销售 Inc'),
 ('F','己集团资金池(尾差承担方)'),
 ('G','庚香港有限公司'),
 ('H','辛新加坡有限公司'),
 ('TREASURY','集团资金部(尾差过渡户)');

INSERT INTO netting_agreement(code, name, cross_currency, settlement_currency, rounding_party) VALUES
 ('NA-MULTI','集团内部同币种多边互抵协议', FALSE, NULL, NULL),
 ('NA-XCCY','集团跨币种互抵协议', TRUE, 'USD', 'F'),
 ('NA-LIMITED','受限互抵协议(仅同币种)', FALSE, NULL, NULL);

INSERT INTO agreement_party(id, agreement_code, entity_code) VALUES
 ('NA-MULTI:A','NA-MULTI','A'),
 ('NA-MULTI:B','NA-MULTI','B'),
 ('NA-MULTI:C','NA-MULTI','C'),
 ('NA-XCCY:D','NA-XCCY','D'),
 ('NA-XCCY:E','NA-XCCY','E'),
 ('NA-XCCY:F','NA-XCCY','F'),
 ('NA-LIMITED:A','NA-LIMITED','A'),
 ('NA-LIMITED:B','NA-LIMITED','B');

-- 内部记账汇率快照（手工维护，不接银行/行情）
INSERT INTO fx_rate(id, from_currency, to_currency, rate, rate_time, source) VALUES
 ('FX-EURUSD-01','EUR','USD',1.0850000000,TIMESTAMPTZ '2026-09-15 09:30:00+00','资金部月中记账汇率'),
 ('FX-USDEUR-01','USD','EUR',0.9216589862,TIMESTAMPTZ '2026-09-15 09:30:00+00','资金部月中记账汇率(倒数)');

-- 场景 1：三方等额环 A->B->C->A，各 1,000,000 CNY
INSERT INTO receivable(id, invoice_no, creditor_code, debtor_code, currency, amount, invoice_date, agreement_code, pledged, disputed, status) VALUES
 ('R-1001','INV-A-1001','A','B','CNY',1000000.00,DATE '2026-08-20','NA-MULTI',FALSE,FALSE,'ACTIVE'),
 ('R-1002','INV-B-1002','B','C','CNY',1000000.00,DATE '2026-08-21','NA-MULTI',FALSE,FALSE,'ACTIVE'),
 ('R-1003','INV-C-1003','C','A','CNY',1000000.00,DATE '2026-08-22','NA-MULTI',FALSE,FALSE,'ACTIVE');

-- 额外一笔非等额同币种债（B 欠 A 250,000），制造一个净付款组，验证非环场景笔数最小化
INSERT INTO receivable(id, invoice_no, creditor_code, debtor_code, currency, amount, invoice_date, agreement_code, pledged, disputed, status) VALUES
 ('R-1004','INV-A-1004','A','B','CNY',250000.00,DATE '2026-08-25','NA-MULTI',FALSE,FALSE,'ACTIVE');

-- 场景 2：跨币种 D/E/F，结算币种 USD，F 为协议约定尾差承担方
-- EUR 200.01 * 1.085 = 217.01085，按 USD 2 位 HALF_UP 取整为 217.01，产生逐笔尾差 -0.00085（债权人 D）
-- 尾差合计的相反数 +0.00085(存储精度) / 现金口径 0.00 归集给 F，且 F 最终是净收款方，形成 1 笔净付款
INSERT INTO receivable(id, invoice_no, creditor_code, debtor_code, currency, amount, invoice_date, agreement_code, pledged, disputed, status) VALUES
 ('R-2001','INV-D-2001','D','E','EUR',200.01,DATE '2026-08-26','NA-XCCY',FALSE,FALSE,'ACTIVE'),
 ('R-2002','INV-E-2002','E','F','USD',300.00,DATE '2026-08-27','NA-XCCY',FALSE,FALSE,'ACTIVE'),
 ('R-2003','INV-F-2003','F','D','USD',217.01,DATE '2026-08-28','NA-XCCY',FALSE,FALSE,'ACTIVE');

-- 场景 3：质押 + 争议，必须排除
INSERT INTO receivable(id, invoice_no, creditor_code, debtor_code, currency, amount, invoice_date, agreement_code, pledged, disputed, status) VALUES
 ('R-3001','INV-A-3001','A','B','CNY',80000.00,DATE '2026-08-29','NA-LIMITED',TRUE,FALSE,'ACTIVE'),
 ('R-3002','INV-B-3002','B','A','CNY',80000.00,DATE '2026-08-30','NA-LIMITED',FALSE,TRUE,'ACTIVE');

-- 场景 4：无互抵协议，保留原债务（两笔都不动）
INSERT INTO receivable(id, invoice_no, creditor_code, debtor_code, currency, amount, invoice_date, agreement_code, pledged, disputed, status) VALUES
 ('R-4001','INV-G-4001','G','H','HKD',50000.00,DATE '2026-08-31',NULL,FALSE,FALSE,'ACTIVE'),
 ('R-4002','INV-H-4002','H','G','HKD',50000.00,DATE '2026-09-01',NULL,FALSE,FALSE,'ACTIVE');
