#!/usr/bin/env bash
# 撤销与冲正端到端验收（demo 种子；用当前时点试算/确认/撤销）
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
j() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{let v=JSON.parse(s);process.argv[1].split(".").forEach(k=>{if(k)v=v?.[k];});console.log(v??"");})' "$1"; }
code() { curl -s -o /tmp/resp.json -w "%{http_code}" -H 'Content-Type: application/json' "$@"; }

NOW=$(node -e "console.log(new Date().toISOString())")
echo "时点 $NOW"

echo "== 试算 + 确认 =="
SID=$(curl -s -X POST $BASE/api/batches/trial -H 'Content-Type: application/json' \
  -d "{\"label\":\"撤销验收\",\"createdBy\":\"专员\",\"valuationTime\":\"$NOW\"}" | j '.id')
CFM=$(curl -s -X POST $BASE/api/batches/$SID/confirm -H 'Content-Type: application/json' -d '{"createdBy":"主管"}')
CID=$(echo "$CFM" | j '.id')
echo "确认批次 $CID 状态=$(echo "$CFM" | j '.status') kind=$(echo "$CFM" | j '.kind')"

echo "== 未撤销确认批次债权保持 CLEARED（质押/争议/无协议 ACTIVE）=="
curl -s $BASE/api/receivables | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const a=JSON.parse(s);
 const cleared=a.filter(r=>r.status==="CLEARED").map(r=>r.id).sort();
 const active=a.filter(r=>r.status==="ACTIVE").map(r=>r.id).sort();
 const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
 A(JSON.stringify(cleared)===JSON.stringify(["R-1001","R-1002","R-1003","R-1004","R-2001","R-2002","R-2003"]),"cleared 集合异常 "+cleared);
 A(JSON.stringify(active)===JSON.stringify(["R-3001","R-3002","R-4001","R-4002"]),"质押/争议/无协议必须 ACTIVE "+active);
 console.log("PASS 直接回归：7 CLEARED / 4 ACTIVE(质押争议无协议)");});'

echo "== 对 SIMULATED 撤销应 409 =="
C=$(code -X POST $BASE/api/batches/$SID/reversal-request -d '{"reason":"x","requestedBy":"x"}'); echo "  http=$C"
[ "$C" = "409" ] || { echo FAIL; exit 1; }

echo "== 发起撤销申请 =="
RR=$(curl -s -X POST $BASE/api/batches/$CID/reversal-request -H 'Content-Type: application/json' \
  -d '{"reason":"账务依据变更","requestedBy":"专员"}')
echo "  申请 $(echo "$RR" | j '.id') 状态 $(echo "$RR" | j '.status')"
[ "$(echo "$RR" | j '.status')" = "REQUESTED" ] || { echo FAIL; exit 1; }

echo "== 重复撤销申请应 409，且只有一条申请 =="
C=$(code -X POST $BASE/api/batches/$CID/reversal-request -d '{"reason":"again","requestedBy":"x"}'); echo "  http=$C body=$(cat /tmp/resp.json | j '.error')"
[ "$C" = "409" ] || { echo FAIL; exit 1; }

echo "== 待审批时再次确认（重复确认）应 409 =="
C=$(code -X POST $BASE/api/batches/$SID/confirm -d '{"createdBy":"x"}'); echo "  http=$C"
[ "$C" = "409" ] || { echo FAIL; exit 1; }

echo "== 审批通过冲正 =="
RVL=$(curl -s -X POST $BASE/api/batches/$CID/reversal/approve -H 'Content-Type: application/json' -d '{"approvedBy":"总会计师"}')
RID=$(echo "$RVL" | j '.id')
node -e '
const r=JSON.parse(process.argv[1]), o=JSON.parse(process.argv[2]);
const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(r.kind==="REVERSAL","冲正批次 kind");
A(r.status==="CONFIRMED","冲正批次生效状态");
A(r.reversesBatchId===o.id,"冲正批次回指原批次");
A(r.valuationTime===o.valuationTime,"冲正沿用原估值时点");
A(r.resultingEntryCount===o.resultingEntryCount,"指令笔数镜像一致");
console.log("PASS 冲正批次",r.id,"回指",r.reversesBatchId,"指令",r.resultingEntryCount);
' "$RVL" "$CFM"

echo "== 原批次 REVERSED + 双向关联 + 审计 =="
ORIG=$(curl -s $BASE/api/batches/$CID)
node -e '
const b=JSON.parse(process.argv[1]);
const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(b.status==="REVERSED","原批次应 REVERSED, 实际 "+b.status);
A(b.reversalBatchId===process.argv[2],"原批次登记冲正批次");
A(b.reversal.status==="PROCESSED","申请状态 PROCESSED");
A(b.reversal.restoredCount===7,"应恢复 7 张, 实际 "+b.reversal.restoredCount);
A(b.reversal.requestedBy==="专员"&&b.reversal.approvedBy==="总会计师","操作人留痕");
A(b.reversal.reason==="账务依据变更","撤销原因留痕");
// 原批次清偿明细仍可追溯
const dis=b.groups.reduce((n,g)=>n+g.discharges.length,0);
A(dis===7,"原批次发票清偿明细应保留 7 行, 实际 "+dis);
console.log("PASS 不可逆审计：原批次 REVERSED，7 张发票清偿明细与操作者完整保留");
' "$ORIG" "$RID"

echo "== 重复审批应 409 且不新增冲正批次 =="
C=$(code -X POST $BASE/api/batches/$CID/reversal/approve -d '{"approvedBy":"x"}'); echo "  http=$C body=$(cat /tmp/resp.json | j '.error')"
[ "$C" = "409" ] || { echo FAIL; exit 1; }
N=$(curl -s $BASE/api/batches | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const a=JSON.parse(s);console.log(a.filter(x=>x.kind==="REVERSAL"&&x.reversesBatchId===process.argv[1]).length)})' "$CID")
[ "$N" = "1" ] || { echo "FAIL 冲正批次数=$N"; exit 1; }
echo "  冲正批次恰好 1 条"

echo "== 债权恢复：环内 7 张回到 ACTIVE，质押/争议/无协议始终 ACTIVE =="
curl -s $BASE/api/receivables | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const a=JSON.parse(s);
 const cleared=a.filter(r=>r.status==="CLEARED");
 const active=a.filter(r=>r.status==="ACTIVE");
 const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
 A(cleared.length===0,"不应再有 CLEARED");
 A(active.length===11,"应全部 11 张 ACTIVE, 实际 "+active.length);
 ["R-3001","R-3002","R-4001","R-4002"].forEach(id=>{const r=a.find(x=>x.id===id);A(r.status==="ACTIVE",id+" 必须始终 ACTIVE");});
 console.log("PASS 恢复后全部 11 张 ACTIVE，质押/争议/无协议从未被改动");});'

echo "== 对已 REVERSED 再撤销应 409 =="
C=$(code -X POST $BASE/api/batches/$CID/reversal-request -d '{}'); echo "  http=$C"
[ "$C" = "409" ] || { echo FAIL; exit 1; }

echo "== 冲正批次本身不可撤销 =="
C=$(code -X POST $BASE/api/batches/$RID/reversal-request -d '{}'); echo "  http=$C"
[ "$C" = "409" ] || { echo FAIL; exit 1; }

echo "== SIMULATED 批次仍可按估值时点确认（回归）=="
S2=$(curl -s -X POST $BASE/api/batches/trial -H 'Content-Type: application/json' \
  -d "{\"label\":\"撤销后再次清算\",\"createdBy\":\"专员\",\"valuationTime\":\"$NOW\"}")
S2ID=$(echo "$S2" | j '.id')
C2=$(curl -s -X POST $BASE/api/batches/$S2ID/confirm -H 'Content-Type: application/json' -d '{"createdBy":"主管"}')
echo "  新确认批次 $(echo "$C2" | j '.id') 状态 $(echo "$C2" | j '.status') 组=$(echo "$C2" | j '.groups.length') 指令=$(echo "$C2" | j '.resultingEntryCount') 排除=$(echo "$C2" | j '.excludedCount')"
[ "$(echo "$C2" | j '.status')" = "CONFIRMED" ] || { echo FAIL; exit 1; }

echo "ALL REVERSAL ACCEPTANCE CHECKS PASSED"
