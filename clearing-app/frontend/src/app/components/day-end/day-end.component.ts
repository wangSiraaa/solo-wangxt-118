import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ClosingReportView } from '../../models/models';

/**
 * 日终关账与再开账页面：按结算日查看报表版本、汇总行、批次贡献链，
 * 并提供关账/再开账申请/四眼审批操作。
 */
@Component({
  selector: 'app-day-end',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './day-end.component.html',
  styleUrls: ['./day-end.component.css']
})
export class DayEndComponent {
  @Input() date = '';
  @Input() reports: ClosingReportView[] = [];
  @Input() busy = false;
  @Output() dateChange = new EventEmitter<string>();
  @Output() close = new EventEmitter<{ date: string; by: string }>();
  @Output() reopen = new EventEmitter<{ date: string; reason: string; by: string }>();
  @Output() decision = new EventEmitter<{ requestId: string; approver: string; comment: string; outcome: 'APPROVE' | 'REJECT' }>();
  @Output() openBatch = new EventEmitter<string>();

  closedBy = '日终主管';
  reopenReason = '账务依据变化，需合规再开账';
  reopenBy = '资金专员';
  approver = '';
  comment = '';
  expanded = new Set<string>();

  onDate(value: string): void {
    this.dateChange.emit(value);
  }

  get latest(): ClosingReportView | null {
    return this.reports.length ? this.reports[0] : null;
  }

  canClose(): boolean {
    return !this.reports.some(r => r.status === 'CLOSED' || r.status === 'REOPEN_PENDING');
  }

  activeReopen(report: ClosingReportView) {
    return report.reopen && (report.reopen.status === 'REQUESTED'
      || report.reopen.status === 'PARTIALLY_APPROVED');
  }

  /** 该日是否任意版本有进行中再开账（用于隐藏其他版本的发起入口）。 */
  hasAnyPending(): boolean {
    return this.reports.some(r => this.activeReopen(r));
  }

  toggle(id: string): void {
    this.expanded.has(id) ? this.expanded.delete(id) : this.expanded.add(id);
  }

  submitClose(): void {
    this.close.emit({ date: this.date, by: this.closedBy });
  }

  submitReopen(): void {
    this.reopen.emit({ date: this.date, reason: this.reopenReason, by: this.reopenBy });
  }

  submitDecision(requestId: string, received: number, required: number, outcome: 'APPROVE' | 'REJECT'): void {
    const approver = this.approver.trim() || (received === 0 ? '复核人甲' : '复核人乙');
    this.decision.emit({
      requestId, approver,
      comment: this.comment || (outcome === 'APPROVE' ? '同意' : '不通过'),
      outcome
    });
    this.comment = '';
  }

  fmt(n: number | string | null | undefined, digits = 2): string {
    if (n === null || n === undefined || n === '') { return '—'; }
    return Number(n).toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits });
  }
}
