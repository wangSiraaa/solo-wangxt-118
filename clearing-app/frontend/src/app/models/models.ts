export type BatchStatus = 'SIMULATED' | 'CONFIRMED' | 'REVERSAL_PENDING' | 'REVERSED' | 'ADJUSTMENT_PENDING';
export type BatchKind = 'NETTING' | 'REVERSAL' | 'ADJUSTMENT';
export type ReversalStatus = 'REQUESTED' | 'PARTIALLY_APPROVED' | 'PROCESSED' | 'REJECTED';
export type DecisionOutcome = 'APPROVE' | 'REJECT';

export interface BatchSummary {
  id: string;
  version: number;
  label: string;
  status: BatchStatus;
  kind: BatchKind;
  reversesBatchId: string | null;
  reversalBatchId: string | null;
  adjustsBatchId: string | null;
  adjustmentBatchId: string | null;
  createdAt: string;
  confirmedAt: string | null;
  originalClaimCount: number;
  resultingEntryCount: number;
  excludedCount: number;
  createdBy: string | null;
}

export interface ReversalDecisionView {
  id: string;
  seq: number;
  outcome: DecisionOutcome;
  approver: string;
  comment: string | null;
  decidedAt: string;
  statusBefore: ReversalStatus;
  statusAfter: ReversalStatus;
  reversalBatchId: string | null;
}

export interface ReversalView {
  id: string;
  originalBatchId: string;
  reversalBatchId: string | null;
  status: ReversalStatus;
  requiredApprovals: number;
  approvalsReceived: number;
  thresholdAgreement: string | null;
  thresholdAmount: string | null;
  grossClearedAmount: string | null;
  grossClearedCurrency: string | null;
  reason: string | null;
  requestedBy: string | null;
  requestedAt: string;
  finalizedBy: string | null;
  processedAt: string | null;
  restoredCount: number | null;
  rejectedBy: string | null;
  rejectedAt: string | null;
  rejectReason: string | null;
  decisions: ReversalDecisionView[];
}
export interface DischargeView {
  receivableId: string;
  invoiceNo: string;
  creditorCode: string;
  debtorCode: string;
  originalCurrency: string;
  originalAmount: number;
  convertedAmount: number;
  setoffAmount: number;
  paymentAmount: number;
  fxRate: number;
}

export interface EntryView {
  type: 'SETOFF' | 'NET_PAYMENT' | 'ROUNDING';
  fromEntity: string;
  toEntity: string;
  amount: number;
  currency: string;
  description: string;
}

export interface PositionView {
  entityCode: string;
  grossReceivable: number;
  grossPayable: number;
  netAmount: number;
}

export interface RoundingView {
  lineType: 'FX_CONVERSION' | 'BEARER_ADJUST';
  entityCode: string;
  currency: string;
  amount: number;
  refInvoiceNo: string | null;
  fxRate: number | null;
  rateTime: string | null;
  note: string | null;
}

export interface GroupView {
  id: string;
  agreementCode: string;
  clearingCurrency: string;
  crossCurrency: boolean;
  claimCount: number;
  grossClaimsDisplay: string;
  netEntryCount: number;
  positions: PositionView[];
  entries: EntryView[];
  discharges: DischargeView[];
  rounding: RoundingView[];
}

export interface ExcludedView {
  receivableId: string;
  invoiceNo: string;
  reason: string;
  detail: string;
}

export interface BatchView extends BatchSummary {
  valuationTime: string;
  reversedAt: string | null;
  reversalRequestedAt: string | null;
  reversal: ReversalView | null;
  adjustment: AdjustmentView | null;
  groups: GroupView[];
  excluded: ExcludedView[];
}

export interface ReceivableView {
  id: string;
  invoiceNo: string;
  creditorCode: string;
  debtorCode: string;
  currency: string;
  amount: number;
  invoiceDate: string;
  agreementCode: string | null;
  pledged: boolean;
  disputed: boolean;
  status: 'ACTIVE' | 'CLEARED' | 'VOID';
}

export interface ReferenceData {
  entities: { code: string; name: string }[];
  agreements: {
    code: string; name: string; crossCurrency: boolean;
    settlementCurrency: string; roundingParty: string;
  }[];
  parties: { agreementCode: string; entityCode: string }[];
}

export interface FxRateView {
  id: string;
  fromCurrency: string;
  toCurrency: string;
  rate: number;
  rateTime: string;
  source: string;
}

export type AdjustmentStatus = 'REQUESTED' | 'PARTIALLY_APPROVED' | 'PROCESSED' | 'REJECTED';

export interface InvoiceCorrectionEventView {
  id: string;
  seq: number;
  receivableId: string;
  invoiceNo: string;
  correctedField: 'AMOUNT' | 'AGREEMENT';
  oldAmount: number;
  oldCurrency: string;
  oldAgreementCode: string | null;
  newAmount: number;
  newCurrency: string;
  newAgreementCode: string | null;
  oldConverted: number;
  newConverted: number;
  deltaConverted: number;
  clearingCurrency: string;
  effectiveScope: string | null;
  reason: string | null;
  requestedBy: string | null;
  createdAt: string;
}

export interface AdjustmentDecisionView {
  id: string;
  seq: number;
  outcome: DecisionOutcome;
  approver: string;
  comment: string | null;
  decidedAt: string;
  statusBefore: AdjustmentStatus;
  statusAfter: AdjustmentStatus;
  adjustmentBatchId: string | null;
}

export interface AdjustmentView {
  id: string;
  originalBatchId: string;
  adjustmentBatchId: string | null;
  status: AdjustmentStatus;
  requiredApprovals: number;
  approvalsReceived: number;
  thresholdAgreement: string | null;
  thresholdAmount: string | null;
  deltaGrossAmount: string | null;
  deltaGrossCurrency: string | null;
  reason: string | null;
  requestedBy: string | null;
  requestedAt: string;
  finalizedBy: string | null;
  processedAt: string | null;
  eventCount: number | null;
  rejectedBy: string | null;
  rejectedAt: string | null;
  rejectReason: string | null;
  events: InvoiceCorrectionEventView[];
  decisions: AdjustmentDecisionView[];
}

export interface AdjustmentSpec {
  receivableId: string;
  newAmount: number | null;
  newAgreementCode: string | null;
  reason: string | null;
  effectiveScope: string | null;
}
