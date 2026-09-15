package com.treasury.clearing.repo;

import com.treasury.clearing.domain.ReopenDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReopenDecisionRepository extends JpaRepository<ReopenDecision, String> {

    @Query("select d from ReopenDecision d where d.request.id = :requestId order by d.seq asc")
    List<ReopenDecision> listByRequest(@Param("requestId") String requestId);
}
