import { CommonModule } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { ClearingApi } from './api/clearing.api';
import { FxPanelComponent } from './components/fx-panel/fx-panel.component';
import { BatchDetailComponent } from './components/batch-detail/batch-detail.component';
import { BatchListComponent } from './components/batch-list/batch-list.component';
import { TrialFormComponent, TrialFormValue } from './components/trial-form/trial-form.component';
import { BatchSummary, BatchView, FxRateView } from './models/models';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, BatchListComponent, BatchDetailComponent, FxPanelComponent,
    TrialFormComponent],
  templateUrl: './app.component.html'
})
export class AppComponent implements OnInit {
  private api = inject(ClearingApi);

  batches = signal<BatchSummary[]>([]);
  rates = signal<FxRateView[]>([]);
  selected = signal<BatchView | null>(null);
  busy = signal(false);
  error = signal<string | null>(null);

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.api.listBatches().subscribe(b => this.batches.set(b));
    this.api.fxRates().subscribe(r => this.rates.set(r));
  }

  createTrial(form: TrialFormValue): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.runTrial(form.label, form.createdBy, form.valuationTime)
      .subscribe({
        next: b => {
          this.busy.set(false);
          this.selected.set(b);
          this.api.listBatches().subscribe(list => this.batches.set(list));
        },
        error: e => this.fail(e, '试算失败')
      });
  }

  openBatch(id: string): void {
    this.api.getBatch(id).subscribe(b => this.selected.set(b));
  }

  closeDetail(): void {
    this.selected.set(null);
    this.refresh();
  }

  confirm(approver: string): void {
    const cur = this.selected();
    if (!cur) { return; }
    this.busy.set(true);
    this.error.set(null);
    this.api.confirm(cur.id, approver).subscribe({
      next: confirmed => {
        this.busy.set(false);
        this.selected.set(confirmed);
        this.api.listBatches().subscribe(list => this.batches.set(list));
      },
      error: e => this.fail(e, '确认失败')
    });
  }

  requestReversal(payload: { reason: string; by: string }): void {
    const cur = this.selected();
    if (!cur) { return; }
    this.busy.set(true);
    this.error.set(null);
    this.api.requestReversal(cur.id, payload.reason, payload.by).subscribe({
      // 申请后回到原批次（此时 REVERSAL_PENDING）
      next: () => {
        this.busy.set(false);
        this.reload(cur.id);
      },
      error: e => this.fail(e, '撤销申请失败')
    });
  }

  submitReversalDecision(payload: { approver: string; comment: string; outcome: 'APPROVE' | 'REJECT' }): void {
    const cur = this.selected();
    if (!cur) { return; }
    this.busy.set(true);
    this.error.set(null);
    // 决议末票凑满门槛时后端在同事务生成冲正批次并返回它；否则返回原批次最新视图。
    this.api.submitDecision(cur.id, payload.approver, payload.comment, payload.outcome)
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.api.listBatches().subscribe(list => this.batches.set(list));
          this.reload(cur.id);
        },
        error: e => this.fail(e, payload.outcome === 'APPROVE' ? '审批失败' : '驳回失败')
      });
  }

  private reload(id: string): void {
    this.api.getBatch(id).subscribe({
      next: b => this.selected.set(b),
      error: () => this.selected.set(null)
    });
  }

  private fail(e: unknown, fallback: string): void {
    this.busy.set(false);
    const msg = (e as { error?: { error?: string } })?.error?.error ?? fallback;
    this.error.set(msg);
  }
}
