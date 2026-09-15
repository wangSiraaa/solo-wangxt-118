package com.treasury.clearing.repo;

import com.treasury.clearing.domain.BatchKind;
import com.treasury.clearing.domain.BatchStatus;
import com.treasury.clearing.domain.ClearingBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ClosingBatchQueryRepository extends JpaRepository<ClearingBatch, String> {

    /**
     * 关账/再开账时取某结算日 [from,to) 内生效的已确认批次（NETTING/ADJUSTMENT/REVERSAL）。
     * 加排他锁，与“关账 vs 确认/撤销末审/更正末审”并发互斥。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select distinct b from ClearingBatch b
            where b.status = :status
              and b.confirmedAt >= :from and b.confirmedAt < :to
              and b.kind in :kinds
            """)
    List<ClearingBatch> findEffectiveBatchesForUpdate(@Param("from") Instant from,
                                                      @Param("to") Instant to,
                                                      @Param("status") BatchStatus status,
                                                      @Param("kinds") List<BatchKind> kinds);
}
