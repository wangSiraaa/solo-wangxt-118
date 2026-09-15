package com.treasury.clearing.repo;

import com.treasury.clearing.domain.ExcludedClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExcludedClaimRepository extends JpaRepository<ExcludedClaim, String> {
    List<ExcludedClaim> findByBatchIdOrderByIdAsc(String batchId);
}
