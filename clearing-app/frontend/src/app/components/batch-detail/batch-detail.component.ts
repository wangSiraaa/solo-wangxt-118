import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BatchView, DischargeView } from '../../models/models';
import { DebtGraphComponent } from '../debt-graph/debt-graph.component';

@Component({
  selector: 'app-batch-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, DebtGraphComponent],
  templateUrl: './batch-detail.component.html'
})
export class BatchDetailComponent {
  @Input({ required: true }) batch!: BatchView;
  @Input() busy = false;
  @Output() confirmed = new EventEmitter<string>();
  @Output() closed = new EventEmitter<void>();

  approver = '';
  selectedInvoices: DischargeView[] = [];
  selectedGroupId = '';

  reasonText(reason: string): string {
    return ({
      PLEDGED: '质押',
      DISPUTED: '争议',
      NO_AGREEMENT: '无互抵协议',
      AGREEMENT_MISMATCH: '协议方不匹配',
      NOT_ACTIVE: '非活跃',
      AGREEMENT_MISCONFIGURED: '协议配置错误'
    } as Record<string, string>)[reason] ?? reason;
  }

  fmt(n: number | null | undefined, digits = 2): string {
    if (n === null || n === undefined) { return '—'; }
    return Number(n).toLocaleString('zh-CN', {
      minimumFractionDigits: digits, maximumFractionDigits: digits
    });
  }

  onEdgeInvoices(invoices: DischargeView[], groupId: string): void {
    this.selectedInvoices = invoices;
    this.selectedGroupId = invoices.length ? groupId : '';
  }

  confirm(): void {
    if (this.batch.status === 'SIMULATED') {
      this.confirmed.emit(this.approver || '资金主管');
    }
  }
}
