#!/usr/bin/env bash
# 日终关账/再开账端到端验收（demo：NA-MULTI 现金 25 万达双审门槛 10 万）
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
j() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{let v=JSON.parse(s);process.argv[1].split(".").forEach(k=>{if(k)v=v?.[k];});console.log(v??"");})' "$1"; }
http() { curl -s -o /tmp/resp.json -w "%{http_code}" -H 'Content-Type: application/json' "$@"; }
assertEq() { [ "$1" = "$2" ] || { echo "FAIL: $3 ($1 != $2)"; exit 1; }; }
export R1 RP R2 CID DATE

NOW=$(node -e "console.log(new Date().toISOString())")
SID=$(curl -s -X POST $BASE/api/batches/trial -H 'Content-Type: application/json' \
  -d "{\"label\":\"关账验收\",\"createdBy\":\"专员\",\"valuationTime\":\"$NOW\"}" | j '.id')
CFM=$(curl -s -X POST $BASE/api/batches/$SID/confirm -H 'Content-Type: application/json' -d '{"createdBy":"主管"}')
CID=$(echo "$CFM" | j '.id')
DATE=$(echo "$CFM" | j '.confirmedAt' | cut -c1-10)
echo "批次 $CID 结算日 $DATE"

echo "== 审批中批次不能关账（发起撤销后立即关账 409，再驳回恢复）=="
curl -s -X POST $BASE/api/batches/$CID/reversal-request -H 'Content-Type: application/json' -d '{"reason":"r","requestedBy":"专员"}' >/dev/null
C=$(http -X POST $BASE/api/closing/$DATE/close -d '{"closedBy":"日终"}'); assertEq "$C" "409" "审批中关账"; echo "  409 审批中不能关账"
curl -s -X POST $BASE/api/batches/$CID/reversal/decision -H 'Content-Type: application/json' -d '{"approver":"预审驳回人","outcome":"REJECT","comment":"x"}' >/dev/null

echo "== 唯一关账 v1，含汇总与贡献链 =="
R1=$(curl -s -X POST $BASE/api/closing/$DATE/close -H 'Content-Type: application/json' -d '{"closedBy":"日终主管"}')
node -e '
const r=JSON.parse(process.env.R1), cid=process.env.CID;
const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(r.reportVersion===1&&r.status==="CLOSED","v1 CLOSED");
A(r.contributions.some(c=>c.batchId===cid&&c.contributionType==="NETTING"),"贡献链含原批次");
A(r.lines.length>0,"有汇总行");
A(Math.abs(r.contributions.reduce((s,c)=>s+c.netPosition,0))<1e-6,"贡献净头寸零和");
const by={};for(const c of r.contributions){by[c.clearingCurrency]=(by[c.clearingCurrency]||0)+(c.cashAmount>0?c.cashAmount:0);}
A(Math.abs(by.CNY-250000)<1e-6,"CNY 现金 25 万, 实际 "+by.CNY);
A(Math.abs(by.USD-82.99)<0.01,"USD 现金 82.99, 实际 "+by.USD);
console.log("PASS v1 关账: 批次",r.batchCount,"汇总行",r.lines.length,"贡献",r.contributions.length,"现金",JSON.stringify(by));'

echo "== 重复关账 409 =="
C=$(http -X POST $BASE/api/closing/$DATE/close -d '{"closedBy":"x"}'); assertEq "$C" "409" "重复关账"; echo "  409"

echo "== 关账后撤销：双审首审 PARTIAL 可落、末审被日期锁 409，批次不冲正 =="
curl -s -X POST $BASE/api/batches/$CID/reversal-request -H 'Content-Type: application/json' -d '{"reason":"r","requestedBy":"关账专员"}' >/dev/null
curl -s -X POST $BASE/api/batches/$CID/reversal/decision -H 'Content-Type: application/json' -d '{"approver":"关账撤销甲","outcome":"APPROVE","comment":"x"}' >/dev/null
C=$(http -X POST $BASE/api/batches/$CID/reversal/decision -d '{"approver":"关账撤销乙","outcome":"APPROVE","comment":"x"}'); assertEq "$C" "409" "末审被日期锁"; echo "  409 撤销冲正被关账拦截"
curl -s $BASE/api/batches/$CID | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const b=JSON.parse(s);const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(b.status==="REVERSAL_PENDING"&&b.reversal.status==="PARTIALLY_APPROVED","应 REVERSAL_PENDING/PARTIAL, 实际 "+b.status+"/"+b.reversal.status);
console.log("PASS 首审 PARTIAL 保留、末审被拦、批次未冲正");});'

echo "== 再开账（双审）：v1 REOPEN_PENDING，自审/重复 409 =="
# 先驳回撤销恢复 CONFIRMED（撤销为一案一审，驳回后不影响关账/再开账）
curl -s -X POST $BASE/api/batches/$CID/reversal/decision -H 'Content-Type: application/json' -d '{"approver":"关账撤销驳回","outcome":"REJECT","comment":"先再开账"}' >/dev/null
RP=$(curl -s -X POST $BASE/api/closing/$DATE/reopen-request -H 'Content-Type: application/json' -d '{"reason":"账务依据变化","requestedBy":"资金专员"}')
node -e 'const r=JSON.parse(process.env.RP);const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(r.status==="REOPEN_PENDING","REOPEN_PENDING, 实际 "+r.status);
A(r.reopen.requiredApprovals===2,"双审");
console.log("PASS 再开账申请",r.reopen.id,"双审");'
RID=$(echo "$RP" | j '.reopen.id')
C=$(http -X POST $BASE/api/closing/reopen/$RID/decision -d '{"approver":"资金专员","outcome":"APPROVE"}'); assertEq "$C" "409" "自审"; echo "  409 自审"
curl -s -X POST $BASE/api/closing/reopen/$RID/decision -H 'Content-Type: application/json' -d '{"approver":"再开甲","outcome":"APPROVE","comment":"ok"}' >/dev/null
C=$(http -X POST $BASE/api/closing/reopen/$RID/decision -d '{"approver":"再开甲","outcome":"APPROVE"}'); assertEq "$C" "409" "重复审批"; echo "  409 重复审批"

echo "== 末审生成 v2，v1 SUPERSEDED，历史贡献保留 =="
R2=$(curl -s -X POST $BASE/api/closing/reopen/$RID/decision -H 'Content-Type: application/json' -d '{"approver":"再开乙","outcome":"APPROVE","comment":"ok"}')
export R2
node -e 'const r=JSON.parse(process.env.R2);const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(r.reportVersion===2&&r.status==="CLOSED","v2 CLOSED");
A(r.reopenReason==="账务依据变化","再开账原因快照");
A(r.reopen.newReportVersion===2,"决议记录新版本");
console.log("PASS 新版本 v2",r.id);'
curl -s $BASE/api/closing/$DATE | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const a=JSON.parse(s);const A=(c,m)=>{if(!c){console.error("FAIL",m);process.exit(1)}};
A(a.length===2,"2 个版本, 实际 "+a.length);
A(a[0].reportVersion===2&&a[0].status==="CLOSED","v2 CLOSED");
A(a[1].reportVersion===1&&a[1].status==="SUPERSEDED","v1 SUPERSEDED");
A(a[1].contributions.length>0,"v1 贡献链保留");
A(a[0].reopen.decisions.length===2,"决议链 2 条");
console.log("PASS 版本链 v1 SUPERSEDED(贡献保留) -> v2 CLOSED");});'

echo "ALL DAY-END CLOSING CHECKS PASSED"
