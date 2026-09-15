import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { BatchStatus, BatchSummary } from '../../models/models';

@Component({
  selector: 'app-batch-list',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './batch-list.component.html'
})
export class BatchListComponent {
  @Input({ required: true }) batches: BatchSummary[] = [];
  @Output() open = new EventEmitter<string>();

  statusText(s: BatchStatus): string {
    return ({
      SIMULATED: '试算',
      CONFIRMED: '已确认',
      REVERSAL_PENDING: '撤销审批中',
      REVERSED: '已冲正',
      ADJUSTMENT_PENDING: '差额更正审批中'
    } as Record<BatchStatus, string>)[s] ?? s;
  }
}
