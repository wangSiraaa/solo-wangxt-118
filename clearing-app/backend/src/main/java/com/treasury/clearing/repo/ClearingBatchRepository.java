package com.treasury.clearing.repo;

import com.treasury.clearing.domain.BatchStatus;
import com.treasury.clearing.domain.ClearingBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ClearingBatchRepository extends JpaRepository<ClearingBatch, String> {
    List<ClearingBatch> findAllByOrderByCreatedAtDesc();
    List<ClearingBatch> findByStatusOrderByCreatedAtDesc(BatchStatus status);

    /** 撤销申请/审批关键路径对批次行加排他锁，与乐观锁双保险串行化并发。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ClearingBatch b where b.id = :id")
    Optional<ClearingBatch> findByIdForUpdate(@Param("id") String id);
}
