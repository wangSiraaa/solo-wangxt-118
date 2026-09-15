import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BatchView, DischargeView } from '../../models/models';

export interface CorrectionDraft {
  receivableId: string;
  invoiceNo: string;
  newAmount: number | null;
  newAgreementCode: string | null;
  reason: string | null;
  effectiveScope: string | null;
}

/**
 * 确认方案差额更正面板（与撤销互斥）。
 * 仅普通 NETTING 批次、CONFIRMED/ADJUSTMENT_PENDING 时出现；可从原批次清偿明细选择发票。
 */
@Component({
  selector: 'app-adjustment-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './adjustment-panel.component.html',
  styleUrls: ['./adjustment-panel.component.css']
})
export class AdjustmentPanelComponent {
  @Input({ required: true }) batch!: BatchView;
  @Input() busy = false;
  @Output() request = new EventEmitter<{
    reason: string; by: string; corrections: {
      receivableId: string; newAmount: number | null; newAgreementCode: string | null;
      reason: string | null; effectiveScope: string | null;
    }[];
  }>();
  @Output() decision = new EventEmitter<{ approver: string; comment: string; outcome: 'APPROVE' | 'REJECT' }>();
  @Output() openBatch = new EventEmitter<string>();

  reason = '个别发票结算前金额更正';
  requestedBy = '资金专员';
  approver = '';
  comment = '';
  effectiveScope = '自下一结算周期起';
  /** 选中的原始发票 id → 更正后金额草稿 */
  drafts = new Map<string, number | null>();

  /** 原批次清偿发票（去重），作为可更正候选。 */
  candidates(): DischargeView[] {
    const seen = new Set<string>();
    const out: DischargeView[] = [];
    for (const g of this.batch.groups) {
      for (const d of g.discharges) {
        if (!seen.has(d.receivableId)) {
          seen.add(d.receivableId);
          out.push(d);
        }
      }
    }
    return out;
  }

  toggle(d: DischargeView, checked: boolean): void {
    if (checked) {
      this.drafts.set(d.receivableId, d.originalAmount);
    } else {
      this.drafts.delete(d.receivableId);
    }
  }

  isChecked(id: string): boolean {
    return this.drafts.has(id);
  }

  thresholdText(): string {
    const a = this.batch.adjustment;
    if (!a || a.requiredApprovals <= 1) {
      return '一审门槛（更正差额未达到协议双审阈值，或协议未配置门槛）';
    }
    return `双审门槛（四眼）· 协议 ${a.thresholdAgreement} 阈值 ${a.thresholdAmount}，`
      + `本次差额 ${a.deltaGrossAmount} ${a.deltaGrossCurrency}`;
  }

  submitRequest(): void {
    const corrections = [...this.drafts.entries()].map(([receivableId, newAmount]) => ({
      receivableId,
      newAmount,
      newAgreementCode: null,
      reason: this.reason,
      effectiveScope: this.effectiveScope
    }));
    if (!corrections.length) { return; }
    this.request.emit({ reason: this.reason, by: this.requestedBy, corrections });
  }

  submitDecision(outcome: 'APPROVE' | 'REJECT'): void {
    const approver = this.approver.trim()
      || (this.batch.adjustment && this.batch.adjustment.approvalsReceived === 0 ? '复核人甲' : '复核人乙');
    this.decision.emit({ approver, comment: this.comment || (outcome === 'APPROVE' ? '同意' : '不通过'), outcome });
  }

  fmt(n: number | string | null | undefined, digits = 2): string {
    if (n === null || n === undefined || n === '') { return '—'; }
    return Number(n).toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits });
  }
}
