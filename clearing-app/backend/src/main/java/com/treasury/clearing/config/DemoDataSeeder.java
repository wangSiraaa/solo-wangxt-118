package com.treasury.clearing.config;

import com.treasury.clearing.domain.AgreementParty;
import com.treasury.clearing.domain.FxRate;
import com.treasury.clearing.domain.LegalEntity;
import com.treasury.clearing.domain.NettingAgreement;
import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.domain.ReceivableStatus;
import com.treasury.clearing.repo.AgreementPartyRepository;
import com.treasury.clearing.repo.FxRateRepository;
import com.treasury.clearing.repo.LegalEntityRepository;
import com.treasury.clearing.repo.NettingAgreementRepository;
import com.treasury.clearing.repo.ReceivableRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * demo profile 的种子数据（用仓库写入，与 Flyway 的 PostgreSQL 种子 V2 内容一致），
 * 便于在没有 PostgreSQL 的环境做端到端 HTTP 验收。
 */
@Configuration
public class DemoDataSeeder {

    @Bean
    CommandLineRunner seed(Environment env, LegalEntityRepository entities,
                           NettingAgreementRepository agreements,
                           AgreementPartyRepository parties,
                           FxRateRepository fx,
                           ReceivableRepository receivables) {
        return args -> {
            boolean demo = env.matchesProfiles("demo");
            if (!demo || !receivables.findAll().isEmpty()) {
                return;
            }

            entities.save(new LegalEntity("A", "甲制造有限公司"));
            entities.save(new LegalEntity("B", "乙贸易有限公司"));
            entities.save(new LegalEntity("C", "丙物流有限公司"));
            entities.save(new LegalEntity("D", "丁欧洲采购 GmbH"));
            entities.save(new LegalEntity("E", "戊美洲销售 Inc"));
            entities.save(new LegalEntity("F", "己集团资金池(尾差承担方)"));
            entities.save(new LegalEntity("G", "庚香港有限公司"));
            entities.save(new LegalEntity("H", "辛新加坡有限公司"));
            entities.save(new LegalEntity("TREASURY", "集团资金部(尾差过渡户)"));

            agreements.save(new NettingAgreement("NA-MULTI", "集团内部同币种多边互抵协议",
                    false, null, null));
            agreements.save(new NettingAgreement("NA-XCCY", "集团跨币种互抵协议",
                    true, "USD", "F"));
            agreements.save(new NettingAgreement("NA-LIMITED", "受限互抵协议(仅同币种)",
                    false, null, null));

            for (String e : new String[]{"A", "B", "C"}) {
                parties.save(new AgreementParty("NA-MULTI", e));
            }
            for (String e : new String[]{"D", "E", "F"}) {
                parties.save(new AgreementParty("NA-XCCY", e));
            }
            parties.save(new AgreementParty("NA-LIMITED", "A"));
            parties.save(new AgreementParty("NA-LIMITED", "B"));

            fx.save(new FxRate("FX-EURUSD-01", "EUR", "USD",
                    new BigDecimal("1.0850000000"),
                    Instant.parse("2026-09-15T09:30:00Z"), "资金部月中记账汇率"));
            fx.save(new FxRate("FX-USDEUR-01", "USD", "EUR",
                    new BigDecimal("0.9216589862"),
                    Instant.parse("2026-09-15T09:30:00Z"), "资金部月中记账汇率(倒数)"));

            LocalDate d = LocalDate.of(2026, 8, 20);
            // 三方等额环
            save(receivables, "R-1001", "INV-A-1001", "A", "B", "CNY", "1000000.00", d, "NA-MULTI", false, false);
            save(receivables, "R-1002", "INV-B-1002", "B", "C", "CNY", "1000000.00", d.plusDays(1), "NA-MULTI", false, false);
            save(receivables, "R-1003", "INV-C-1003", "C", "A", "CNY", "1000000.00", d.plusDays(2), "NA-MULTI", false, false);
            // 非等额：多 25 万
            save(receivables, "R-1004", "INV-A-1004", "A", "B", "CNY", "250000.00", d.plusDays(5), "NA-MULTI", false, false);
            // 跨币种（产生逐笔尾差 -0.00085）
            save(receivables, "R-2001", "INV-D-2001", "D", "E", "EUR", "200.01", d.plusDays(6), "NA-XCCY", false, false);
            save(receivables, "R-2002", "INV-E-2002", "E", "F", "USD", "300.00", d.plusDays(7), "NA-XCCY", false, false);
            save(receivables, "R-2003", "INV-F-2003", "F", "D", "USD", "217.01", d.plusDays(8), "NA-XCCY", false, false);
            // 质押 / 争议
            save(receivables, "R-3001", "INV-A-3001", "A", "B", "CNY", "80000.00", d.plusDays(9), "NA-LIMITED", true, false);
            save(receivables, "R-3002", "INV-B-3002", "B", "A", "CNY", "80000.00", d.plusDays(10), "NA-LIMITED", false, true);
            // 无协议
            save(receivables, "R-4001", "INV-G-4001", "G", "H", "HKD", "50000.00", d.plusDays(11), null, false, false);
            save(receivables, "R-4002", "INV-H-4002", "H", "G", "HKD", "50000.00", d.plusDays(12), null, false, false);
        };
    }

    private static void save(ReceivableRepository repo, String id, String invoice,
                             String creditor, String debtor, String ccy, String amount,
                             LocalDate date, String agreement, boolean pledged,
                             boolean disputed) {
        repo.save(new Receivable(id, invoice, creditor, debtor, ccy, new BigDecimal(amount),
                date, agreement, pledged, disputed, ReceivableStatus.ACTIVE));
    }
}
