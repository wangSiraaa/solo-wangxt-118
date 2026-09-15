import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BatchView } from '../../models/models';

/**
 * 分级四眼撤销面板。
 * 申请门槛在批次撤销申请上快照：requiredApprovals=1 一审，=2 双审。
 * 每次审批新增不可改决议；审批人不得是申请人，同一审批人不能重复提交。
 */
@Component({
  selector: 'app-reversal-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './reversal-panel.component.html',
  styleUrls: ['./reversal-panel.component.css']
})
export class ReversalPanelComponent {
  @Input({ required: true }) batch!: BatchView;
  @Input() busy = false;
  @Output() request = new EventEmitter<{ reason: string; by: string }>();
  @Output() decision = new EventEmitter<{ approver: string; comment: string; outcome: 'APPROVE' | 'REJECT' }>();
  @Output() openBatch = new EventEmitter<string>();

  reason = '账务依据变更，需冲正本次确认';
  requestedBy = '资金专员';
  approver = '';
  comment = '';

  /** 默认审批人建议：申请人之外的两个四眼角色。 */
  get suggestedApprover(): string {
    const required = this.batch.reversal?.requiredApprovals ?? 1;
    const received = this.batch.reversal?.approvalsReceived ?? 0;
    if (required >= 2) {
      return received === 0 ? '复核人甲' : '复核人乙';
    }
    return '复核人甲';
  }

  get effectiveApprover(): string {
    return this.approver.trim() || this.suggestedApprover;
  }

  thresholdText(): string {
    const r = this.batch.reversal;
    if (!r || r.requiredApprovals <= 1) {
      return '一审门槛（清偿额未达到协议双审阈值，或协议未配置门槛）';
    }
    return `双审门槛（四眼）· 协议 ${r.thresholdAgreement} 阈值 ${r.thresholdAmount}，`
      + `本组清偿 ${r.grossClearedAmount} ${r.grossClearedCurrency}`;
  }

  submit(outcome: 'APPROVE' | 'REJECT'): void {
    this.decision.emit({
      approver: this.effectiveApprover,
      comment: this.comment || (outcome === 'APPROVE' ? '同意' : '不通过'),
      outcome
    });
    this.comment = '';
  }
}
