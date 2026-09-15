package com.treasury.clearing.repo;

import com.treasury.clearing.domain.AdjustmentDecision;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AdjustmentDecisionRepository extends JpaRepository<AdjustmentDecision, String> {

    @Query("select d from AdjustmentDecision d where d.request.id = :requestId order by d.seq asc")
    List<AdjustmentDecision> listByRequest(@Param("requestId") String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from AdjustmentDecision d where d.request.id = :requestId order by d.seq asc")
    List<AdjustmentDecision> listByRequestForUpdate(@Param("requestId") String requestId);
}
