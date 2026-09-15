package com.treasury.clearing.repo;

import com.treasury.clearing.domain.FxRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FxRateRepository extends JpaRepository<FxRate, String> {
    List<FxRate> findByFromCurrencyAndToCurrencyOrderByRateTimeDesc(String from, String to);
}
