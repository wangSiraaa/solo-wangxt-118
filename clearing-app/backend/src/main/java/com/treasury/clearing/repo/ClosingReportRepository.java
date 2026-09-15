package com.treasury.clearing.repo;

import com.treasury.clearing.domain.ClosingReport;
import com.treasury.clearing.domain.ClosingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ClosingReportRepository extends JpaRepository<ClosingReport, String> {

    List<ClosingReport> findBySettlementDateOrderByReportVersionDesc(LocalDate date);

    /** 关账/再开账关键路径对该日全部报表行加排他锁，串行化并发。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ClosingReport r where r.settlementDate = :date order by r.reportVersion desc")
    List<ClosingReport> findByDateForUpdate(@Param("date") LocalDate date);

    @Query("select r from ClosingReport r where r.settlementDate = :date and r.status = :status order by r.reportVersion desc")
    Optional<ClosingReport> findByDateAndStatus(@Param("date") LocalDate date,
                                                @Param("status") ClosingStatus status);

    @Query("select count(r) from ClosingReport r where r.settlementDate = :date and r.status in :statuses")
    long countByDateAndStatusIn(@Param("date") LocalDate date,
                                @Param("statuses") List<ClosingStatus> statuses);
}
