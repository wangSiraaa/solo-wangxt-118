import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';

export interface TrialFormValue {
  label: string;
  createdBy: string;
  /** 估值时点，ISO-8601（UTC） */
  valuationTime: string;
}

/** 把 ISO 瞬时转成 datetime-local 控件需要的本地时间字符串（精确到分钟）。 */
export function instantToLocalInput(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T`
    + `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

@Component({
  selector: 'app-trial-form',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="panel trial-form">
      <h2>新建清算试算</h2>
      <p class="small muted">
        试算只保存 <span class="badge sim">SIMULATED</span> 方案，不改动任何原始债权、不发起银行付款。
      </p>
      <div class="form-grid">
        <label>方案名称
          <input name="label" [(ngModel)]="label" [placeholder]="defaultLabel">
        </label>
        <label>操作人
          <input name="createdBy" [(ngModel)]="createdBy" placeholder="资金专员">
        </label>
        <label>估值时点（取不晚于该时点的最新内部记账汇率）
          <input name="valuationTime" type="datetime-local" [(ngModel)]="valuationLocal" required>
        </label>
      </div>
      <div class="form-foot">
        <span class="small muted">生效时点(UTC)：{{ valuationUtc || '—' }}</span>
        <button [disabled]="busy || !valuationUtc" (click)="submit()">
          {{ busy ? '试算中…' : '开始试算' }}</button>
      </div>
    </div>
  `,
  styles: [`
    .form-grid { display: grid; grid-template-columns: 1fr 1fr 1.4fr; gap: 10px; }
    label { display: flex; flex-direction: column; gap: 4px; color: var(--muted); font-size: 12px; }
    input { width: 100%; }
    .form-foot { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-top: 12px; }
    @media (max-width: 900px) { .form-grid { grid-template-columns: 1fr; } }
  `]
})
export class TrialFormComponent {
  @Output() create = new EventEmitter<TrialFormValue>();
  @Input() busy = false;

  readonly defaultLabel = '资金部清算试算 ' + new Date().toLocaleString('zh-CN');
  label = '';
  createdBy = '资金专员';
  valuationLocal = instantToLocalInput(new Date().toISOString());

  get valuationUtc(): string {
    if (!this.valuationLocal) { return ''; }
    const d = new Date(this.valuationLocal);
    return isNaN(d.getTime()) ? '' : d.toISOString();
  }

  submit(): void {
    if (!this.valuationUtc) { return; }
    this.create.emit({
      label: this.label.trim() || this.defaultLabel,
      createdBy: this.createdBy.trim() || '资金专员',
      valuationTime: this.valuationUtc
    });
  }
}
