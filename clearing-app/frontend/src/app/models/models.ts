export interface BatchSummary {
  id: string;
  label: string;
  status: 'SIMULATED' | 'CONFIRMED';
  createdAt: string;
  confirmedAt: string | null;
  originalClaimCount: number;
  resultingEntryCount: number;
  excludedCount: number;
  createdBy: string | null;
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
