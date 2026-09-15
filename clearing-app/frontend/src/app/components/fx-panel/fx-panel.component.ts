import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';
import { FxRateView } from '../../models/models';

@Component({
  selector: 'app-fx-panel',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="panel fx-panel">
      <h2>汇率快照</h2>
      <p class="small muted">资金部手工维护的内部记账汇率，带时点与来源；系统不接入外部行情或银行。</p>
      <table>
        <thead><tr><th>币种对</th><th class="num">汇率</th><th>时点</th><th>来源</th></tr></thead>
        <tbody>
        <tr *ngFor="let f of rates">
          <td>{{ f.fromCurrency }} → {{ f.toCurrency }}</td>
          <td class="num">{{ f.rate }}</td>
          <td class="small">{{ f.rateTime }}</td>
          <td class="small muted">{{ f.source }}</td>
        </tr>
        </tbody>
      </table>
    </div>
  `
})
export class FxPanelComponent {
  @Input({ required: true }) rates: FxRateView[] = [];
}
