import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { BatchSummary } from '../../models/models';

@Component({
  selector: 'app-batch-list',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './batch-list.component.html'
})
export class BatchListComponent {
  @Input({ required: true }) batches: BatchSummary[] = [];
  @Input() busy = false;
  @Output() open = new EventEmitter<string>();
  @Output() createTrial = new EventEmitter<void>();
}
