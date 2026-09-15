package com.treasury.clearing.repo;

import com.treasury.clearing.domain.ClosingContribution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClosingContributionRepository extends JpaRepository<ClosingContribution, String> {
    List<ClosingContribution> findByReportIdOrderByAgreementCodeAscClearingCurrencyAscBatchIdAsc(String reportId);
}
