package com.treasury.clearing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 协议参与方。只有某协议的参与方之间、且债权挂在该协议下，才允许互抵。 */
@Entity
@Table(name = "agreement_party")
public class AgreementParty {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "agreement_code", nullable = false, length = 32)
    private String agreementCode;

    @Column(name = "entity_code", nullable = false, length = 32)
    private String entityCode;

    protected AgreementParty() {
    }

    public AgreementParty(String agreementCode, String entityCode) {
        this.id = agreementCode + ":" + entityCode;
        this.agreementCode = agreementCode;
        this.entityCode = entityCode;
    }

    public String getAgreementCode() {
        return agreementCode;
    }

    public String getEntityCode() {
        return entityCode;
    }
}
