package com.treasury.clearing.dto;

import java.math.BigDecimal;
import java.util.List;

public record BatchView(String id,
                        long version,
                        String label,
                        String status,
                        String kind,
                        String reversesBatchId,
                        String reversalBatchId,
                        String createdAt,
                        String valuationTime,
                        String confirmedAt,
                        String reversedAt,
                        String reversalRequestedAt,
                        int originalClaimCount,
                        int resultingEntryCount,
                        int excludedCount,
                        String createdBy,
                        ReversalView reversal,
                        List<GroupView> groups,
                        List<ExcludedView> excluded) {

    public record GroupView(String id,
                            String agreementCode,
                            String clearingCurrency,
                            boolean crossCurrency,
                            int claimCount,
                            String grossClaimsDisplay,
                            int netEntryCount,
                            List<PositionView> positions,
                            List<EntryView> entries,
                            List<DischargeView> discharges,
                            List<RoundingView> rounding) {
    }

    public record PositionView(String entityCode,
                               BigDecimal grossReceivable,
                               BigDecimal grossPayable,
                               BigDecimal netAmount) {
    }

    public record EntryView(String type,
                            String fromEntity,
                            String toEntity,
                            BigDecimal amount,
                            String currency,
                            String description) {
    }

    public record DischargeView(String receivableId,
                                String invoiceNo,
                                String creditorCode,
                                String debtorCode,
                                String originalCurrency,
                                BigDecimal originalAmount,
                                BigDecimal convertedAmount,
                                BigDecimal setoffAmount,
                                BigDecimal paymentAmount,
                                BigDecimal fxRate) {
    }

    public record RoundingView(String lineType,
                               String entityCode,
                               String currency,
                               BigDecimal amount,
                               String refInvoiceNo,
                               BigDecimal fxRate,
                               String rateTime,
                               String note) {
    }

    public record ExcludedView(String receivableId,
                               String invoiceNo,
                               String reason,
                               String detail) {
    }
}
