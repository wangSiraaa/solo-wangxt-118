package com.treasury.clearing.repo;

import com.treasury.clearing.domain.SettlementDayGate;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SettlementDayGateRepository extends JpaRepository<SettlementDayGate, Long> {

    @Query("select coalesce(max(g.generation), 0) from SettlementDayGate g where g.settlementDate = :date")
    int maxGeneration(@Param("date") LocalDate date);

    @Query("select g from SettlementDayGate g where g.settlementDate = :date order by g.generation desc")
    List<SettlementDayGate> listByDate(@Param("date") LocalDate date);

    /** 当前进行中的关账门（PENDING）。 */
    @Query("select g from SettlementDayGate g where g.activeToken = :token")
    Optional<SettlementDayGate> findPendingByToken(@Param("token") String token);

    /** 加排他锁读取当前 PENDING 门。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from SettlementDayGate g where g.activeToken = :token")
    Optional<SettlementDayGate> findPendingByTokenForUpdate(@Param("token") String token);
}
