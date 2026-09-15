package com.treasury.clearing.repo;

import com.treasury.clearing.domain.ClosingReportLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClosingReportLineRepository extends JpaRepository<ClosingReportLine, String> {
    List<ClosingReportLine> findByReportIdOrderByAgreementCodeAscClearingCurrencyAscEntityCodeAsc(String reportId);
}
