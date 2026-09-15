package com.treasury.clearing.repo;

import com.treasury.clearing.domain.AdjustmentRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AdjustmentRequestRepository extends JpaRepository<AdjustmentRequest, String> {

    @Query("select r from AdjustmentRequest r where r.originalBatch.id = :batchId")
    Optional<AdjustmentRequest> findByOriginalBatchId(@Param("batchId") String batchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AdjustmentRequest r where r.originalBatch.id = :batchId")
    Optional<AdjustmentRequest> findByOriginalBatchIdForUpdate(@Param("batchId") String batchId);

    @Query("select count(r) > 0 from AdjustmentRequest r where r.originalBatch.id = :batchId")
    boolean existsByOriginalBatchId(@Param("batchId") String batchId);
}
