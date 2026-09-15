import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ClosingReportView } from '../models/models';

@Injectable({ providedIn: 'root' })
export class ClosingApi {
  private http = inject(HttpClient);

  list(date: string): Observable<ClosingReportView[]> {
    return this.http.get<ClosingReportView[]>(`/api/closing/${date}`);
  }

  close(date: string, closedBy: string): Observable<ClosingReportView> {
    return this.http.post<ClosingReportView>(`/api/closing/${date}/close`, { closedBy });
  }

  requestReopen(date: string, reason: string, requestedBy: string): Observable<ClosingReportView> {
    return this.http.post<ClosingReportView>(`/api/closing/${date}/reopen-request`,
      { reason, requestedBy });
  }

  decide(requestId: string, approver: string, comment: string,
         outcome: 'APPROVE' | 'REJECT'): Observable<ClosingReportView> {
    return this.http.post<ClosingReportView>(`/api/closing/reopen/${requestId}/decision`,
      { approver, comment, outcome });
  }
}
