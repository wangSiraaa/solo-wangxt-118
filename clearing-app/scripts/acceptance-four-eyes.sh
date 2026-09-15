#!/usr/bin/env bash
# 分级四眼审批端到端验收（demo：NA-MULTI 清偿额 325 万 ≥ 门槛 10 万 → 双审）
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
j() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{let v=JSON.parse(s);process.argv[1].split(".").forEach(k=>{if(k)v=v?.[k];});console.log(v??"");})' "$1"; }
http() { curl -s -o /tmp/resp.json -w "%{http_code}" -H 'Content-Type: application/json' "$@"; }
assertEq() { [ "$1" = "$2" ] || { echo "FAIL: $3 ($1 != $2)"; exit 1; }; }

NOW=$(node -e "console.log(new Date().toISOString())")
SID=$(curl -s -X POST $BASE/api/batches/trial -H 'Content-Type: application/json' \
  -d "{\"label\":\"四眼验收\",\"createdBy\":\"专员\",\"valuationTime\":\"$NOW\"}" | j '.id')
CID=$(curl -s -X POST $BASE/api/batches/$SID/confirm -H 'Content-Type: application/json' -d '{"createdBy":"主管"}' | j '.id')
echo "确认批次 $CID"

echo "== 发起撤销申请（申请人=专员），应快照为双审 =="
RR=$(curl -s -X POST $BASE/api/batches/$CID/reversal-request -H 'Content-Type: application/json' \
  -d '{"reason":"账务依据变更","requestedBy":"专员"}')
node -e '
const r=JSON.parse(process.argv[1]);const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(r.requiredApprovals===2,"required 应为 2, 实际 "+r.requiredApprovals);
A(r.approvalsReceived===0,"received 应为 0");
A(r.status==="REQUESTED","status REQUESTED");
A(r.thresholdAgreement==="NA-MULTI","门槛协议应为 NA-MULTI, 实际 "+r.thresholdAgreement);
console.log("PASS 双审门槛快照: "+r.thresholdAgreement+" 阈值 "+r.thresholdAmount
  +", 最高组清偿 "+r.grossClearedAmount+" "+r.grossClearedCurrency);
' "$RR"

echo "== 申请人自审必须 409，且不产生决议 =="
C=$(http -X POST $BASE/api/batches/$CID/reversal/decision -d '{"approver":"专员","comment":"自审","outcome":"APPROVE"}')
assertEq "$C" "409" "自审应 409"; echo "  409 $(cat /tmp/resp.json | j '.error')"

echo "== 一审（复核人甲）通过：PARTIALLY_APPROVED，无冲正、债权仍 CLEARED =="
D1=$(curl -s -X POST $BASE/api/batches/$CID/reversal/decision -H 'Content-Type: application/json' \
  -d '{"approver":"复核人甲","comment":"同意一审","outcome":"APPROVE"}')
node -e '
const b=JSON.parse(process.argv[1]);const r=b.reversal;const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(r.status==="PARTIALLY_APPROVED","一审后状态 "+r.status);
A(r.approvalsReceived===1,"received=1");
A(!r.reversalBatchId,"一审不应生成冲正批次");
A(r.decisions.length===1,"决议应 1 条");
A(r.decisions[0].statusBefore==="REQUESTED"&&r.decisions[0].statusAfter==="PARTIALLY_APPROVED","决议前后状态留痕");
console.log("PASS 一审: 1/2, 决议链",r.decisions.map(d=>d.seq+":"+d.approver+":"+d.outcome).join(","));
' "$D1"
curl -s $BASE/api/receivables | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const a=JSON.parse(s);
 if(a.filter(r=>r.status==="CLEARED").length!==7){console.error("FAIL 一审不应动债权");process.exit(1)} console.log("PASS 一审后债权仍 7 张 CLEARED");});'

echo "== 同一审批人重复提交 409，不占用第二名额 =="
C=$(http -X POST $BASE/api/batches/$CID/reversal/decision -d '{"approver":"复核人甲","comment":"重复","outcome":"APPROVE"}')
assertEq "$C" "409" "重复审批应 409"; echo "  409 $(cat /tmp/resp.json | j '.error')"

echo "== 两个不同审批人并发抢最后名额，只成功一次 =="
SEQ_OK=/tmp/seq_ok; SEQ_CF=/tmp/seq_cf; : > $SEQ_OK; : > $SEQ_CF
for who in 抢批A 抢批B 抢批C; do
  ( C=$(http -X POST $BASE/api/batches/$CID/reversal/decision -d "{\"approver\":\"$who\",\"comment\":\"race\",\"outcome\":\"APPROVE\"}");
    [ "$C" = "200" ] || [ "$C" = "201" ] && echo x >> $SEQ_OK || echo x >> $SEQ_CF ) &
done
wait
OK=$(wc -l < $SEQ_OK); CF=$(wc -l < $SEQ_CF)
assertEq "$OK" "1" "最后名额只应成功一次(成功=$OK)"; assertEq "$CF" "2" "其余应冲突(冲突=$CF)"
echo "  成功 1 / 冲突 2"

echo "== 冲正结果：PROCESSED、唯一冲正批次、7 张恢复、决议链 2 条 =="
FINAL=$(curl -s $BASE/api/batches/$CID)
node -e '
const b=JSON.parse(process.argv[1]);const r=b.reversal;const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(b.status==="REVERSED","原批次 REVERSED");
A(r.status==="PROCESSED","申请 PROCESSED");
A(r.approvalsReceived===2,"2/2");
A(r.restoredCount===7,"恢复 7 张");
A(/^RVL-/.test(r.reversalBatchId),"冲正批次号");
A(r.decisions.length===2,"决议链应恰好 2 条, 实际 "+r.decisions.length);
const last=r.decisions[r.decisions.length-1];
A(last.statusAfter==="PROCESSED"&&last.reversalBatchId===r.reversalBatchId,"末审决议带冲正批次");
A(r.decisions[0].approver==="复核人甲","首审审批人留痕");
console.log("PASS 双审完成:",r.reversalBatchId,"决议",r.decisions.map(d=>d.approver).join("->"));
' "$FINAL"

N=$(curl -s $BASE/api/batches | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).filter(x=>x.kind==="REVERSAL"&&x.reversesBatchId===process.argv[1]).length))' "$CID")
assertEq "$N" "1" "冲正批次唯一"
curl -s $BASE/api/receivables | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const a=JSON.parse(s);
 const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
 A(a.filter(r=>r.status==="ACTIVE").length===11,"应 11 张 ACTIVE");
 ["R-3001","R-3002","R-4001","R-4002"].forEach(id=>A(a.find(r=>r.id===id).status==="ACTIVE",id+" 始终 ACTIVE"));
 console.log("PASS 恢复后 11 ACTIVE，质押/争议/无协议从未改动");});'

echo "== 冲正后任何再审批/撤销均 409 =="
C=$(http -X POST $BASE/api/batches/$CID/reversal/decision -d '{"approver":"多余的人","outcome":"APPROVE"}'); assertEq "$C" "409" "终态再审批 409"
C=$(http -X POST $BASE/api/batches/$CID/reversal-request -d '{}'); assertEq "$C" "409" "已冲正再撤销 409"

echo "ALL FOUR-EYES ACCEPTANCE CHECKS PASSED"
