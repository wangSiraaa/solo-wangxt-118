import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BatchView } from '../../models/models';

/**
 * 已确认批次的撤销/冲正面板。
 * 状态：CONFIRMED（可发起申请）→ REVERSAL_PENDING（可审批/驳回）→ REVERSED（终态，只展示审计）。
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
  @Output() approve = new EventEmitter<string>();
  @Output() reject = new EventEmitter<{ by: string; reason: string }>();
  @Output() openBatch = new EventEmitter<string>();

  reason = '账务依据变更，需冲正本次确认';
  requestedBy = '资金专员';
  approver = '资金主管';
  rejectReason = '';
}
