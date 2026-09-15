import { CommonModule } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { ClearingApi } from './api/clearing.api';
import { FxPanelComponent } from './components/fx-panel/fx-panel.component';
import { BatchDetailComponent } from './components/batch-detail/batch-detail.component';
import { BatchListComponent } from './components/batch-list/batch-list.component';
import { BatchSummary, BatchView, FxRateView } from './models/models';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, BatchListComponent, BatchDetailComponent, FxPanelComponent],
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

  createTrial(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.runTrial('资金部清算试算 ' + new Date().toLocaleString('zh-CN'), '资金专员')
      .subscribe({
        next: b => {
          this.busy.set(false);
          this.selected.set(b);
          this.api.listBatches().subscribe(list => this.batches.set(list));
        },
        error: e => {
          this.busy.set(false);
          this.error.set(e?.error?.error ?? '试算失败');
        }
      });
  }

  openBatch(id: string): void {
    this.api.getBatch(id).subscribe(b => this.selected.set(b));
  }

  closeDetail(): void {
    this.selected.set(null);
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
      error: e => {
        this.busy.set(false);
        this.error.set(e?.error?.error ?? '确认失败');
      }
    });
  }
}
