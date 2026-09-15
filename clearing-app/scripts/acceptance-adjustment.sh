#!/usr/bin/env bash
# 差额更正端到端验收（demo：NA-MULTI 门槛 10 万；发票增/减 10 万 → 双审）
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
j() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{let v=JSON.parse(s);process.argv[1].split(".").forEach(k=>{if(k)v=v?.[k];});console.log(v??"");})' "$1"; }
http() { curl -s -o /tmp/resp.json -w "%{http_code}" -H 'Content-Type: application/json' "$@"; }
assertEq() { [ "$1" = "$2" ] || { echo "FAIL: $3 ($1 != $2)"; exit 1; }; }

NOW=$(node -e "console.log(new Date().toISOString())")
SID=$(curl -s -X POST $BASE/api/batches/trial -H 'Content-Type: application/json' \
  -d "{\"label\":\"差额验收\",\"createdBy\":\"专员\",\"valuationTime\":\"$NOW\"}" | j '.id')
CFM=$(curl -s -X POST $BASE/api/batches/$SID/confirm -H 'Content-Type: application/json' -d '{"createdBy":"主管"}')
CID=$(echo "$CFM" | j '.id')
echo "确认批次 $CID"

# 找环内发票 R-1001（A→B，100 万）。增额到 110 万，|Δ|=10 万达双审门槛。
RID=$(echo "$CFM" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const b=JSON.parse(s);const d=b.groups.flatMap(g=>g.discharges).find(x=>x.invoiceNo==="INV-A-1001");console.log(d.receivableId);})')
echo "更正发票 $RID (INV-A-1001) 100万 -> 110万"

echo "== 发起差额更正（双审）=="
AR=$(curl -s -X POST $BASE/api/batches/$CID/adjustment-request -H 'Content-Type: application/json' \
  -d "{\"reason\":\"结算前增额\",\"requestedBy\":\"专员\",\"corrections\":[{\"receivableId\":\"$RID\",\"newAmount\":1100000,\"effectiveScope\":\"2026-09 起\"}]}")
node -e '
const a=JSON.parse(process.argv[1]);const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(a.requiredApprovals===2,"required=2, 实际 "+a.requiredApprovals);
A(a.status==="REQUESTED","REQUESTED");
A(a.events.length===1,"1 条事件");
const e=a.events[0];
A(e.oldAmount===1000000&&e.newAmount===1100000,"旧/新金额");
A(e.deltaConverted===100000,"差额 +100000, 实际 "+e.deltaConverted);
A(e.correctedField==="AMOUNT","金额更正");
console.log("PASS 更正事件旧值/新值/差额:",e.oldAmount,"->",e.newAmount,"Δ",e.deltaConverted,e.clearingCurrency);
' "$AR"
curl -s $BASE/api/batches/$CID | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const b=JSON.parse(s);if(b.status!=="ADJUSTMENT_PENDING"){console.error("FAIL 批次应为 ADJUSTMENT_PENDING, 实际 "+b.status);process.exit(1)}console.log("PASS 批次进入 ADJUSTMENT_PENDING");});'

echo "== 自审 409、撤销互斥 409 =="
C=$(http -X POST $BASE/api/batches/$CID/adjustment/decision -d '{"approver":"专员","outcome":"APPROVE"}'); assertEq "$C" "409" "自审"; echo "  409 自审"
C=$(http -X POST $BASE/api/batches/$CID/reversal-request -d '{"reason":"x","requestedBy":"x"}'); assertEq "$C" "409" "更正中撤销"; echo "  409 撤销互斥"

echo "== 首审通过：PARTIAL，无差额批次，原债权不变 =="
D1=$(curl -s -X POST $BASE/api/batches/$CID/adjustment/decision -H 'Content-Type: application/json' -d '{"approver":"复核人甲","comment":"ok","outcome":"APPROVE"}')
node -e 'const b=JSON.parse(process.argv[1]);const a=b.adjustment;
const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(a.status==="PARTIALLY_APPROVED","PARTIAL, 实际 "+a.status);
A(!a.adjustmentBatchId,"首审不应生成差额批次");
A(a.decisions.length===1,"1 条决议");
console.log("PASS 首审 1/2，无差额批次");' "$D1"
curl -s $BASE/api/receivables | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const r=JSON.parse(s).find(x=>x.id===process.argv[1]);
const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(r.amount===1000000,"原始金额不应改变");A(r.status==="CLEARED","原始状态应仍 CLEARED");
console.log("PASS 原债权金额/状态不变:",r.amount,r.status);});' "$RID"

echo "== 同一审批人重复 409 =="
C=$(http -X POST $BASE/api/batches/$CID/adjustment/decision -d '{"approver":"复核人甲","outcome":"APPROVE"}'); assertEq "$C" "409" "重复审批"; echo "  409 重复"

echo "== 三个不同审批人并发抢末票，只成功一次 =="
OKF=/tmp/aok; CFF=/tmp/acf; : > $OKF; : > $CFF
for who in 末审A 末审B 末审C; do
 ( C=$(http -X POST $BASE/api/batches/$CID/adjustment/decision -d "{\"approver\":\"$who\",\"outcome\":\"APPROVE\"}");
   { [ "$C" = "200" ] || [ "$C" = "201" ]; } && echo x>>$OKF || echo x>>$CFF ) &
done
wait
assertEq "$(wc -l <$OKF)" "1" "末票只成功一次"; assertEq "$(wc -l <$CFF)" "2" "其余冲突"
echo "  成功 1 / 冲突 2"

echo "== 结果：唯一差额批次、差额指令 B→A 100000、原批次仍 CONFIRMED =="
FINAL=$(curl -s $BASE/api/batches/$CID)
node -e '
const b=JSON.parse(process.argv[1]);const a=b.adjustment;const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(b.status==="CONFIRMED","原批次保持 CONFIRMED, 实际 "+b.status);
A(a.status==="PROCESSED"&&a.approvalsReceived===2,"2/2 PROCESSED");
A(/^ADJ-/.test(a.adjustmentBatchId),"差额批次号");
A(a.events.length===1&&a.decisions.length===2,"1 事件 + 2 决议");
console.log("PASS 差额批次",a.adjustmentBatchId);' "$FINAL"
ADJ=$(echo "$FINAL" | j '.adjustment.adjustmentBatchId')
curl -s $BASE/api/batches/$ADJ | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const b=JSON.parse(s);
const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(b.kind==="ADJUSTMENT","kind=ADJUSTMENT");
A(b.adjustsBatchId===process.argv[1],"回指原批次");
A(b.valuationTime===JSON.parse(process.argv[2]).valuationTime,"沿用原估值时点");
const e=b.groups[0].entries;
A(e.length===1,"1 条差额指令");
A(e[0].type==="ADJUSTMENT"&&e[0].fromEntity==="B"&&e[0].toEntity==="A"&&e[0].amount===100000,"B->A 100000");
console.log("PASS 差额指令:",e[0].fromEntity,"->",e[0].toEntity,e[0].amount,e[0].currency);});' "$CID" "$FINAL"

N=$(curl -s $BASE/api/batches | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).filter(x=>x.kind==="ADJUSTMENT"&&x.adjustsBatchId===process.argv[1]).length))' "$CID")
assertEq "$N" "1" "差额批次唯一"

echo "== 已更正批次不能再更正/撤销；末审后再决议 409 =="
C=$(http -X POST $BASE/api/batches/$CID/adjustment-request -H 'Content-Type: application/json' -d "{\"reason\":\"x\",\"requestedBy\":\"x\",\"corrections\":[{\"receivableId\":\"$RID\",\"newAmount\":1000000}]}"); assertEq "$C" "409" "重复更正"
C=$(http -X POST $BASE/api/batches/$CID/reversal-request -d '{"reason":"x","requestedBy":"x"}'); assertEq "$C" "409" "更正后撤销"
C=$(http -X POST $BASE/api/batches/$CID/adjustment/decision -d '{"approver":"多余","outcome":"APPROVE"}'); assertEq "$C" "409" "终态再决议"

echo "== 原批次清偿明细/指令仍完整、原始债权不变 =="
curl -s $BASE/api/batches/$CID | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const b=JSON.parse(s);
const dis=b.groups.reduce((n,g)=>n+g.discharges.length,0);
const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(dis===7,"原 7 行清偿明细仍在, 实际 "+dis);
A(b.groups.reduce((n,g)=>n+g.entries.length,0)===2,"原 2 条指令仍在");
console.log("PASS 原方案清偿明细 7 行、指令 2 条完整可追溯");});'
curl -s $BASE/api/receivables | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const a=JSON.parse(s);
const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
const r=a.find(x=>x.id===process.argv[1]);A(r.amount===1000000&&r.status==="CLEARED","被更正发票原值/状态");
["R-3001","R-3002","R-4001","R-4002"].forEach(id=>A(a.find(x=>x.id===id).status==="ACTIVE",id+" 始终 ACTIVE"));
console.log("PASS 原始债权未被改动；质押/争议/无协议仍 ACTIVE");});' "$RID"

echo "ALL INVOICE-ADJUSTMENT CHECKS PASSED"
