package com.treasury.clearing.repo;

import com.treasury.clearing.domain.AgreementParty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgreementPartyRepository extends JpaRepository<AgreementParty, String> {
    List<AgreementParty> findByAgreementCode(String agreementCode);
}
