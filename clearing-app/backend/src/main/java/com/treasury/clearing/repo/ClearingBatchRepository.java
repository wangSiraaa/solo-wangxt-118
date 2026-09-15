package com.treasury.clearing.repo;

import com.treasury.clearing.domain.BatchStatus;
import com.treasury.clearing.domain.ClearingBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClearingBatchRepository extends JpaRepository<ClearingBatch, String> {
    List<ClearingBatch> findAllByOrderByCreatedAtDesc();
    List<ClearingBatch> findByStatusOrderByCreatedAtDesc(BatchStatus status);
}
