package com.treasury.clearing.dto;

import java.math.BigDecimal;

public record ReceivableView(String id,
                             String invoiceNo,
                             String creditorCode,
                             String debtorCode,
                             String currency,
                             BigDecimal amount,
                             String invoiceDate,
                             String agreementCode,
                             boolean pledged,
                             boolean disputed,
                             String status) {
}
