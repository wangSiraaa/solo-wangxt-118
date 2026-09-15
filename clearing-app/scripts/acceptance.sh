#!/usr/bin/env bash
# 端到端 HTTP 验收脚本（后端需已启动，demo 种子含 2026-09-15 09:30 的 EUR->USD 汇率）
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
VT="2026-09-15T10:00:00Z"

echo "== 1. 新建试算（不改动原始债权） =="
TRIAL=$(curl -s -X POST "$BASE/api/batches/trial" -H 'Content-Type: application/json' \
  -d "{\"label\":\"验收脚本\",\"createdBy\":\"验收员\",\"valuationTime\":\"$VT\"}")
SID=$(echo "$TRIAL" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>console.log(JSON.parse(s).id))")
echo "trial=$SID"

echo "$TRIAL" | node -e "
let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{
 const b=JSON.parse(s);
 const cny=b.groups.find(g=>g.clearingCurrency==='CNY');
 const usd=b.groups.find(g=>g.clearingCurrency==='USD');
 const assert=(c,m)=>{ if(!c){console.error('FAIL:',m);process.exit(1);} };
 assert(b.originalClaimCount===11,'原始债权应为 11');
 assert(b.resultingEntryCount===2,'环形+跨币种应收敛为 2 笔指令');
 assert(b.excludedCount===4,'应排除质押/争议/无协议共 4 张');
 assert(cny.entries.length===1 && cny.entries[0].type==='NET_PAYMENT'
   && cny.entries[0].amount===250000,'三方环应只剩 B->A 25 万一笔');
 assert(usd.entries.length===1 && usd.entries[0].amount===82.99,'跨币种应只剩 1 笔 82.99');
 const rs=usd.rounding.reduce((a,r)=>a+r.amount,0);
 assert(Math.abs(rs)<1e-6,'跨币种尾差必须零和, 实际 '+rs);
 console.log('PASS trial: 三方环缩减为 1 笔, 跨币种 1 笔, 尾差零和');
});"

echo "== 2. 确认试算 =="
CFM=$(curl -s -X POST "$BASE/api/batches/$SID/confirm" -H 'Content-Type: application/json' -d '{"createdBy":"主管"}')
echo "$CFM" | node -e "
let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{
 const b=JSON.parse(s);
 const assert=(c,m)=>{ if(!c){console.error('FAIL:',m);process.exit(1);} };
 assert(b.status==='CONFIRMED','确认后状态应为 CONFIRMED');
 assert(b.valuationTime==='$VT','确认必须沿用试算估值时点');
 console.log('PASS confirm:', b.id, b.status);
});"

echo "== 3. 原始债权状态（确认后环内 CLEARED，其余 ACTIVE 原债务保留） =="
curl -s "$BASE/api/receivables" | node -e "
let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{
 const a=JSON.parse(s);
 const cleared=a.filter(r=>r.status==='CLEARED').map(r=>r.id).sort();
 const active=a.filter(r=>r.status==='ACTIVE').map(r=>r.id).sort();
 const wantC=['R-1001','R-1002','R-1003','R-1004','R-2001','R-2002','R-2003'];
 const wantA=['R-3001','R-3002','R-4001','R-4002'];
 const eq=(x,y)=>JSON.stringify(x)===JSON.stringify(y);
 const assert=(c,m)=>{ if(!c){console.error('FAIL:',m);process.exit(1);} };
 assert(eq(cleared,wantC),'CLEARED 集合不符: '+cleared);
 assert(eq(active,wantA),'质押/争议/无协议必须保留 ACTIVE: '+active);
 console.log('PASS 原债务保留:', active.join(','));
});"
echo "ALL ACCEPTANCE CHECKS PASSED"
