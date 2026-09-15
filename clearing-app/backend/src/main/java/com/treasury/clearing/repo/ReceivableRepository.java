package com.treasury.clearing.repo;

import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.domain.ReceivableStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReceivableRepository extends JpaRepository<Receivable, String> {
    List<Receivable> findByStatus(ReceivableStatus status);
    List<Receivable> findAllByOrderByInvoiceDateAsc();
}
