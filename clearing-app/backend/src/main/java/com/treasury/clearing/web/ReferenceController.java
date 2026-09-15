package com.treasury.clearing.web;

import com.treasury.clearing.repo.AgreementPartyRepository;
import com.treasury.clearing.repo.LegalEntityRepository;
import com.treasury.clearing.repo.NettingAgreementRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reference")
public class ReferenceController {

    private final LegalEntityRepository entityRepo;
    private final NettingAgreementRepository agreementRepo;
    private final AgreementPartyRepository partyRepo;

    public ReferenceController(LegalEntityRepository entityRepo,
                               NettingAgreementRepository agreementRepo,
                               AgreementPartyRepository partyRepo) {
        this.entityRepo = entityRepo;
        this.agreementRepo = agreementRepo;
        this.partyRepo = partyRepo;
    }

    @GetMapping
    public Map<String, Object> all() {
        return Map.of(
                "entities", entityRepo.findAll().stream()
                        .map(e -> Map.of("code", e.getCode(), "name", e.getName())).toList(),
                "agreements", agreementRepo.findAll().stream()
                        .map(a -> Map.of(
                                "code", a.getCode(),
                                "name", a.getName(),
                                "crossCurrency", a.isCrossCurrency(),
                                "settlementCurrency",
                                a.getSettlementCurrency() == null ? "" : a.getSettlementCurrency(),
                                "roundingParty",
                                a.getRoundingParty() == null ? "" : a.getRoundingParty()))
                        .toList(),
                "parties", partyRepo.findAll().stream()
                        .map(p -> Map.of("agreementCode", p.getAgreementCode(),
                                "entityCode", p.getEntityCode())).toList());
    }
}
