import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { DischargeView, EntryView, GroupView } from '../../models/models';

interface Node {
  code: string;
  x: number;
  y: number;
  net: number;
}

interface Edge {
  id: string;
  from: string;
  to: string;
  currency: string;
  gross: number;       // 原始发票金额（清算币种）
  setoff: number;      // 被互抵金额
  payment: number;     // 净付款金额
  invoices: DischargeView[];
  kind: 'gross';
}

interface NetArrow {
  from: string;
  to: string;
  amount: number;
  currency: string;
  type: string;
}

/**
 * 单个清算组的债务图：
 *  - 灰色粗边 = 原始发票债务（点击可查看被抵销到哪些原始发票）
 *  - 绿色边 = 轧差后净付款；紫色虚线 = 金额 0 的互抵闭环
 *  - 节点颜色表示净收款/净付款
 */
@Component({
  selector: 'app-debt-graph',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './debt-graph.component.html',
  styleUrls: ['./debt-graph.component.css']
})
export class DebtGraphComponent implements OnChanges {
  @Input({ required: true }) group!: GroupView;
  @Output() edgeSelected = new EventEmitter<DischargeView[]>();

  width = 720;
  height = 460;
  cx = 360;
  cy = 235;
  radius = 170;

  nodes: Node[] = [];
  edges: Edge[] = [];
  arrows: NetArrow[] = [];
  selectedEdgeId: string | null = null;

  private nodeIndex = new Map<string, Node>();

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['group'] && this.group) {
      this.layout();
    }
  }

  private layout(): void {
    const entities = new Set<string>();
    const netMap = new Map<string, number>();
    this.group.positions.forEach(p => {
      entities.add(p.entityCode);
      netMap.set(p.entityCode, p.netAmount);
    });
    this.group.discharges.forEach(d => {
      entities.add(d.debtorCode);
      entities.add(d.creditorCode);
      netMap.set(d.creditorCode, netMap.get(d.creditorCode) ?? 0);
      netMap.set(d.debtorCode, netMap.get(d.debtorCode) ?? 0);
    });

    const codes = [...entities].sort();
    this.nodeIndex.clear();
    this.nodes = codes.map((code, i) => {
      const angle = -Math.PI / 2 + (i * 2 * Math.PI) / Math.max(codes.length, 1);
      const node: Node = {
        code,
        x: this.cx + this.radius * Math.cos(angle),
        y: this.cy + this.radius * Math.sin(angle),
        net: netMap.get(code) ?? 0
      };
      this.nodeIndex.set(code, node);
      return node;
    });

    // 按 债务人->债权人 聚合原始发票边
    const edgeMap = new Map<string, Edge>();
    for (const d of this.group.discharges) {
      const key = `${d.debtorCode}->${d.creditorCode}`;
      let edge = edgeMap.get(key);
      if (!edge) {
        edge = {
          id: key, from: d.debtorCode, to: d.creditorCode,
          currency: this.group.clearingCurrency,
          gross: 0, setoff: 0, payment: 0, invoices: [], kind: 'gross'
        };
        edgeMap.set(key, edge);
      }
      edge.gross += d.convertedAmount;
      edge.setoff += d.setoffAmount;
      edge.payment += d.paymentAmount;
      edge.invoices.push(d);
    }
    this.edges = [...edgeMap.values()];

    this.arrows = this.group.entries
      .filter(e => e.type === 'NET_PAYMENT' || (e.type === 'SETOFF' && e.amount === 0))
      .map((e: EntryView) => ({
        from: e.fromEntity, to: e.toEntity, amount: e.amount,
        currency: e.currency, type: e.type
      }));
  }

  selectEdge(edge: Edge): void {
    this.selectedEdgeId = this.selectedEdgeId === edge.id ? null : edge.id;
    this.edgeSelected.emit(this.selectedEdgeId ? edge.invoices : []);
  }

  nodeClass(net: number): string {
    return net > 0 ? 'receiver' : net < 0 ? 'payer' : 'flat';
  }

  fmt(n: number): string {
    return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  /** 起点/终点收缩到节点圆边缘，并对同方向反向边做弯曲。 */
  edgePath(from: string, to: string, bend: number): string {
    const a = this.nodeIndex.get(from)!;
    const b = this.nodeIndex.get(to)!;
    const r = 30;
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const len = Math.hypot(dx, dy) || 1;
    const ux = dx / len;
    const uy = dy / len;
    const sx = a.x + ux * r;
    const sy = a.y + uy * r;
    const tx = b.x - ux * r;
    const ty = b.y - uy * r;
    if (bend === 0) {
      return `M ${sx} ${sy} L ${tx} ${ty}`;
    }
    const mx = (sx + tx) / 2 - uy * bend;
    const my = (sy + ty) / 2 + ux * bend;
    return `M ${sx} ${sy} Q ${mx} ${my} ${tx} ${ty}`;
  }

  labelPos(from: string, to: string, bend: number): { x: number; y: number } {
    const a = this.nodeIndex.get(from)!;
    const b = this.nodeIndex.get(to)!;
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const len = Math.hypot(dx, dy) || 1;
    const ux = dx / len;
    const uy = dy / len;
    const mx = (a.x + b.x) / 2 - uy * bend;
    const my = (a.y + b.y) / 2 + ux * bend;
    return { x: mx, y: my - 8 };
  }

  arrowMarker(end: 'gross' | 'net'): string {
    return end === 'net' ? 'url(#arrowNet)' : 'url(#arrowGross)';
  }

  bendFor(edge: Edge): number {
    const reverseExists = this.edges.some(
      e => e.from === edge.to && e.to === edge.from);
    return reverseExists ? 36 : 0;
  }
}
