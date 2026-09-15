#!/usr/bin/env bash
# 撤销决议冲突路径验收（demo：NA-MULTI 触发双审）：
# 一审通过后二审驳回 → 批次回 CONFIRMED、债权仍 CLEARED、决议链保留、再审批 409、无冲正批次。
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
j() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{let v=JSON.parse(s);process.argv[1].split(".").forEach(k=>{if(k)v=v?.[k];});console.log(v??"");})' "$1"; }
http() { curl -s -o /tmp/resp.json -w "%{http_code}" -H 'Content-Type: application/json' "$@"; }
assertEq() { [ "$1" = "$2" ] || { echo "FAIL: $3 ($1 != $2)"; exit 1; }; }

NOW=$(node -e "console.log(new Date().toISOString())")
SID=$(curl -s -X POST $BASE/api/batches/trial -H 'Content-Type: application/json' \
  -d "{\"label\":\"驳回路径\",\"createdBy\":\"专员\",\"valuationTime\":\"$NOW\"}" | j '.id')
CID=$(curl -s -X POST $BASE/api/batches/$SID/confirm -H 'Content-Type: application/json' -d '{"createdBy":"主管"}' | j '.id')
curl -s -X POST $BASE/api/batches/$CID/reversal-request -H 'Content-Type: application/json' \
  -d '{"reason":"依据待核","requestedBy":"专员"}' >/dev/null
echo "批次 $CID 已发起撤销（双审）"

# 一审通过
curl -s -X POST $BASE/api/batches/$CID/reversal/decision -H 'Content-Type: application/json' \
  -d '{"approver":"复核人甲","comment":"同意一审","outcome":"APPROVE"}' >/tmp/d1.json
node -e 'const r=JSON.parse(require("fs").readFileSync("/tmp/d1.json")).reversal;
const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(r.status==="PARTIALLY_APPROVED","一审后应为 PARTIALLY_APPROVED, 实际 "+r.status);
console.log("PASS 一审通过 1/2");'

# 自审 409（哪怕在 PARTIAL 阶段）
C=$(http -X POST $BASE/api/batches/$CID/reversal/decision -d '{"approver":"专员","outcome":"APPROVE"}')
assertEq "$C" "409" "申请人自审 409"

# 二审驳回
curl -s -X POST $BASE/api/batches/$CID/reversal/decision -H 'Content-Type: application/json' \
  -d '{"approver":"复核人乙","comment":"依据仍有效","outcome":"REJECT"}' >/tmp/d2.json
node -e 'const b=JSON.parse(require("fs").readFileSync("/tmp/d2.json"));const r=b.reversal;
const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(b.status==="CONFIRMED","驳回后批次应回 CONFIRMED, 实际 "+b.status);
A(r.status==="REJECTED","申请应 REJECTED");
A(r.decisions.length===2,"决议链应保留 2 条, 实际 "+r.decisions.length);
A(r.rejectedBy==="复核人乙","驳回人留痕");
A(r.decisions[1].statusAfter==="REJECTED","驳回决议后状态 REJECTED");
console.log("PASS 二审驳回：批次回 CONFIRMED，决议链 2 条（通过+驳回）");'

# 债权仍 CLEARED，质押/争议/无协议 ACTIVE
curl -s $BASE/api/receivables | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const a=JSON.parse(s);
 const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
 A(a.filter(r=>r.status==="CLEARED").length===7,"驳回后环内 7 张仍 CLEARED");
 ["R-3001","R-3002","R-4001","R-4002"].forEach(id=>A(a.find(r=>r.id===id).status==="ACTIVE",id+" 始终 ACTIVE"));
 console.log("PASS 驳回不动债权：7 CLEARED / 4 排除 ACTIVE");});'

# 无冲正批次
N=$(curl -s $BASE/api/batches | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).filter(x=>x.kind==="REVERSAL"&&x.reversesBatchId===process.argv[1]).length))' "$CID")
assertEq "$N" "0" "驳回不应生成冲正批次"

# 驳回后再批准/再驳回均 409，决议不增加
C=$(http -X POST $BASE/api/batches/$CID/reversal/decision -d '{"approver":"复核人丙","outcome":"APPROVE"}'); assertEq "$C" "409" "驳回后再批准 409"
C=$(http -X POST $BASE/api/batches/$CID/reversal/decision -d '{"approver":"复核人甲","outcome":"REJECT"}'); assertEq "$C" "409" "同人重复/终态再决议 409"
DL=$(curl -s $BASE/api/batches/$CID | j '.reversal.decisions.length')
assertEq "$DL" "2" "终态决议数不得增加"
echo "PASS 驳回为终态：再审批/重复决议全部 409，决议仍 2 条"

echo "ALL REJECT-PATH CHECKS PASSED"
