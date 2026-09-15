package com.treasury.clearing.repo;

import com.treasury.clearing.domain.ReversalRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReversalRequestRepository extends JpaRepository<ReversalRequest, String> {

    @Query("select r from ReversalRequest r where r.originalBatch.id = :batchId")
    Optional<ReversalRequest> findByOriginalBatchId(@Param("batchId") String batchId);

    /** 审批处理时对申请行加排他锁，串行化并发审批/重试。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReversalRequest r where r.originalBatch.id = :batchId")
    Optional<ReversalRequest> findByOriginalBatchIdForUpdate(@Param("batchId") String batchId);

    @Query("select count(r) > 0 from ReversalRequest r where r.originalBatch.id = :batchId")
    boolean existsByOriginalBatchId(@Param("batchId") String batchId);

    @Query("select r from ReversalRequest r where r.originalBatch.id = :batchId order by r.requestedAt desc")
    List<ReversalRequest> listByBatch(@Param("batchId") String batchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReversalRequest r where r.originalBatch.id = :batchId order by r.requestedAt desc")
    List<ReversalRequest> findByOriginalBatchIdOrderByRequestedAtDescForUpdate(@Param("batchId") String batchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from ReversalRequest r
            where r.originalBatch.id = :batchId
              and r.status in (com.treasury.clearing.domain.ReversalStatus.REQUESTED,
                               com.treasury.clearing.domain.ReversalStatus.PARTIALLY_APPROVED)
            """)
    java.util.Optional<ReversalRequest> findActiveByBatch(@Param("batchId") String batchId);
}
