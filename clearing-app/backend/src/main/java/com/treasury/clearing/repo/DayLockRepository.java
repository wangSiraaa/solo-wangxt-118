package com.treasury.clearing.repo;

import com.treasury.clearing.domain.SettlementDayLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface DayLockRepository extends JpaRepository<SettlementDayLock, LocalDate> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from SettlementDayLock l where l.settlementDate = :date")
    Optional<SettlementDayLock> findLockedByDate(@Param("date") LocalDate date);
}
