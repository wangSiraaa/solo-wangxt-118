package com.treasury.clearing.repo;

import com.treasury.clearing.domain.InvoiceCorrectionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InvoiceCorrectionEventRepository extends JpaRepository<InvoiceCorrectionEvent, String> {

    @Query("select e from InvoiceCorrectionEvent e where e.request.id = :requestId order by e.seq asc")
    List<InvoiceCorrectionEvent> listByRequest(@Param("requestId") String requestId);
}
