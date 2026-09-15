package com.treasury.clearing.repo;

import com.treasury.clearing.domain.ReopenRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ReopenRequestRepository extends JpaRepository<ReopenRequest, String> {

    @Query("select r from ReopenRequest r where r.settlementDate = :date order by r.requestedAt desc")
    List<ReopenRequest> listByDate(@Param("date") LocalDate date);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReopenRequest r where r.settlementDate = :date and r.status in :statuses")
    List<ReopenRequest> findActiveByDateForUpdate(@Param("date") LocalDate date,
                                                  @Param("statuses")
                                                  List<com.treasury.clearing.domain.ReopenStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReopenRequest r where r.id = :id")
    Optional<ReopenRequest> findByIdForUpdate(@Param("id") String id);
}
