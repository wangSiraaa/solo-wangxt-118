package com.treasury.clearing.repo;

import com.treasury.clearing.domain.Receivable;
import com.treasury.clearing.domain.ReceivableStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ReceivableRepository extends JpaRepository<Receivable, String> {
    List<Receivable> findByStatus(ReceivableStatus status);
    List<Receivable> findAllByOrderByInvoiceDateAsc();

    /** 清偿/恢复关键路径对相关债权加排他锁，防止撤销与再次确认并发重复改状态。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Receivable r where r.id in :ids")
    List<Receivable> findAllByIdForUpdate(@Param("ids") Collection<String> ids);
}
