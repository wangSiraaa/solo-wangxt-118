package com.treasury.clearing.repo;

import com.treasury.clearing.domain.ReversalDecision;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReversalDecisionRepository extends JpaRepository<ReversalDecision, String> {

    List<ReversalDecision> findByRequestIdOrderBySeqAsc(String requestId);

    @Query("select d from ReversalDecision d where d.request.id = :requestId order by d.seq asc")
    List<ReversalDecision> listByRequest(@Param("requestId") String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ReversalDecision d where d.request.id = :requestId order by d.seq asc")
    List<ReversalDecision> listByRequestForUpdate(@Param("requestId") String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ReversalDecision d where d.request.id = :requestId and d.approver = :approver")
    Optional<ReversalDecision> findByRequestAndApproverForUpdate(
            @Param("requestId") String requestId, @Param("approver") String approver);

    @Query("select count(d) from ReversalDecision d where d.request.id = :requestId and d.outcome = com.treasury.clearing.domain.ReversalDecision.Outcome.APPROVE")
    long countApprovals(@Param("requestId") String requestId);
}
