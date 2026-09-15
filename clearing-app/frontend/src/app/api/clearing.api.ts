import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { BatchSummary, BatchView, FxRateView, ReceivableView, ReferenceData, ReversalView } from '../models/models';

@Injectable({ providedIn: 'root' })
export class ClearingApi {
  private http = inject(HttpClient);

  listBatches(): Observable<BatchSummary[]> {
    return this.http.get<BatchSummary[]>('/api/batches');
  }

  getBatch(id: string): Observable<BatchView> {
    return this.http.get<BatchView>(`/api/batches/${id}`);
  }

  runTrial(label: string, createdBy: string, valuationTime: string): Observable<BatchView> {
    return this.http.post<BatchView>('/api/batches/trial', { label, createdBy, valuationTime });
  }

  confirm(id: string, createdBy: string): Observable<BatchView> {
    return this.http.post<BatchView>(`/api/batches/${id}/confirm`, { createdBy });
  }

  requestReversal(id: string, reason: string, requestedBy: string): Observable<ReversalView> {
    return this.http.post<ReversalView>(`/api/batches/${id}/reversal-request`,
      { reason, requestedBy });
  }

  approveReversal(id: string, approvedBy: string): Observable<BatchView> {
    return this.http.post<BatchView>(`/api/batches/${id}/reversal/approve`, { approvedBy });
  }

  rejectReversal(id: string, approvedBy: string, reason: string): Observable<ReversalView> {
    return this.http.post<ReversalView>(`/api/batches/${id}/reversal/reject`,
      { approvedBy, reason });
  }

  receivables(): Observable<ReceivableView[]> {
    return this.http.get<ReceivableView[]>('/api/receivables');
  }

  fxRates(): Observable<FxRateView[]> {
    return this.http.get<FxRateView[]>('/api/fx-rates');
  }

  reference(): Observable<ReferenceData> {
    return this.http.get<ReferenceData>('/api/reference');
  }
}
