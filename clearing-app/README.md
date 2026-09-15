# 集团内部债务多边互抵清算试算平台

围绕「甲欠乙、乙欠丙、丙又欠甲」这类**内部环形债务**做清算试算：按**互抵协议 + 币种边界**分组，
在**不改变任何法人净头寸**的前提下尽量压缩收付款笔数；跨币种时逐项记录汇率、时点与尾差归属；
资金人员可在 **Angular 债务图**上从净结算指令一路追溯到被抵销的**原始发票**。

> 平台只做台账试算与确认，**不接入任何真实银行通道**。`NET_PAYMENT` 永远是「模拟付款指令」。

---

## 1. 业务规则（硬约束）

| 规则 | 实现 |
|---|---|
| 只在**同一互抵协议**参与方之间互抵 | 债权必须挂靠协议，且债权人/债务人都是协议参与方，否则 `NO_AGREEMENT` / `AGREEMENT_MISMATCH` |
| 按**币种边界**分组 | 协议 `cross_currency=false` 时按发票币种拆组；`true` 时全组折到协议结算币种 |
| **质押债权**不互抵 | `pledged=true` → `PLEDGED`，原始债务保留 |
| **争议债权**不互抵 | `disputed=true` → `DISPUTED`，原始债务保留 |
| 各法人**净头寸不能改变** | 引擎内断言：每组净头寸合计为 0；逐主体「毛应收−毛应付」= 净头寸 |
| **小额尾差**不得破坏余额 | 现金净头寸只按已取整发票金额轧差；逐笔换汇尾差与承担方归集行**零和单列**，不产生收付 |
| 试算 vs 确认严格区分 | 试算 `SIMULATED` 不改原始债权；确认 `CONFIRMED` 仅把发票状态置 `CLEARED`，金额字段不动 |
| 不接真实银行 | 无任何出金接口；确认只写台账状态与批次留痕 |

被排除的债权**不删除、不改金额**，逐张写入 `excluded_claim` 并附原因，原始债务完整保留。

---

## 2. 清算算法（`backend` · `NettingEngine`，全程 `BigDecimal` / `HALF_UP`）

1. **分组**：`(互抵协议, 清算币种)`。同币种组清算币种=发票币种；跨币种组=协议结算币种。
2. **折算**：跨币种发票 `金额 × 汇率`，先保留 6 位中间精度，再按**目标币种记账精度**取整
   （USD/EUR/CNY=2 位，JPY/KRW=0 位，KWD=3 位）。逐笔尾差 = 取整值 − 精确换算值。
3. **净头寸**：每个主体 `Σ应收 − Σ应付`。引擎断言全组合计为 0，否则直接报错（不允许带病出方案）。
4. **多边最小笔数结算**：把净债权人/净债务人按最大额贪心配对。
   - 三方等额环 A→B→C→A：三个净头寸全为 0 → **0 笔资金收付**，仅留一条金额 0 的 `SETOFF` 互抵闭环；
   - 非等额（A 多收 25 万）：收敛为 **1 笔** B→A 净付款，其余全部互抵。
5. **发票级追溯**：把轧差结果按 FIFO 映射回每张发票，拆成 `setoff_amount + payment_amount`，
   恒有 `互抵 + 付款 = 折算额`。前端点击债务图上的债务边，即可列出这条边被抵销的全部原始发票。
6. **尾差归属**：逐笔尾差记在发票债权人名下（`FX_CONVERSION`，带汇率/时点/关联发票），
   再把其相反数一笔归集到协议约定承担方（`BEARER_ADJUST`）。两行合计为 0，
   **不进入现金净头寸、不生成收付指令**，因此其他法人余额一分不动。

---

## 3. 技术栈与结构

- **后端**：Spring Boot 3.3 / Java 17 / Spring Data JPA / Flyway / PostgreSQL（生产默认）；
  H2 仅用于测试与本地 `demo`。所有金额 `NUMERIC(20,6)`，汇率 `NUMERIC(20,10)`。
- **前端**：Angular 17（standalone + signals），债务图为**原生 SVG**（环形布局、债务边可点击、
  净付款/互抵闭环分层着色），无重型图数据库或图表库。

```
clearing-app/
├── backend/src/main/java/com/treasury/clearing/
│   ├── calc/        # NettingEngine：净头寸/最小笔数/FIFO 发票分配（纯函数，可单测）
│   ├── domain/      # JPA 实体：原始债权、批次、组、净头寸、指令、发票清偿、尾差、排除
│   ├── repo/        # Spring Data 仓库
│   ├── service/     # TrialService 编排（资格筛选→分组→折算→引擎→持久化）
│   ├── web/         # REST 控制器 + 统一异常处理
│   └── config/      # demo profile 种子数据
├── backend/src/main/resources/db/migration/
│   ├── V1__schema.sql      # PostgreSQL 表结构（清算批次 + 不可变原始债权）
│   └── V2__demo_data.sql   # 覆盖四类验收场景的演示数据
├── frontend/src/app/
│   ├── components/debt-graph/   # SVG 债务图（点边追溯发票）
│   ├── components/batch-detail/ # 净头寸/指令/发票追溯/跨币种尾差/排除清单/确认
│   ├── components/batch-list/、fx-panel/
│   ├── api/、models/
└── scripts/acceptance.sh   # 端到端 HTTP 验收
```

### 关键 REST API

| 方法/路径 | 说明 |
|---|---|
| `POST /api/batches/trial` | 试算（请求体可带 `valuationTime`，缺省=当前时刻），**不动原始债权** |
| `POST /api/batches/{id}/confirm` | 确认试算方案；沿用试算的估值时点重新计算，发票转 `CLEARED` |
| `GET /api/batches` / `GET /api/batches/{id}` | 批次列表 / 完整明细（组、头寸、指令、发票清偿、尾差、排除） |
| `GET /api/receivables` | 原始债权只读视图 |
| `GET|POST /api/fx-rates` | 资金部手工维护内部记账汇率（币种对、汇率、时点、来源） |
| `GET /api/reference` | 法人、协议、参与方 |

---

## 4. 运行

### 4.1 PostgreSQL + 后端（推荐，Flyway 自动建表并写入演示数据）

```bash
cd clearing-app
docker compose up --build
# API: http://localhost:8080/api/batches
```

### 4.2 无数据库时的本地演示（H2 内存库）

```bash
# 需要 JDK17 与 Maven
bash scripts/run-demo.sh
# 等价于 java -jar backend/target/internal-clearing-1.0.0.jar --spring.profiles.active=demo
```

### 4.3 前端

```bash
cd frontend
npm install
npm start          # http://localhost:4200，通过 proxy.conf.json 代理到 :8080
```

> demo 种子里的内部记账汇率时点是 `2026-09-15T09:30:00Z`。通过界面「新建试算」时若本机时钟早于
> 该时点，请直接调用 `POST /api/batches/trial` 并在请求体带 `"valuationTime":"2026-09-15T10:00:00Z"`
> （系统只取**不晚于估值时点**的最新汇率，这是刻意的时点控制，不是缺陷）。

### 4.4 测试与验收

```bash
cd backend && mvn test                 # 9 个测试：引擎纯单测 + 服务级集成 + 全量守恒
bash scripts/acceptance.sh             # 黑盒 HTTP 验收（需先启动 demo 实例）
```

---

## 5. 验收场景与实测结果（demo 数据，估值时点 2026-09-15T10:00Z）

原始 11 张活跃债权，试算后**只有 2 笔执行指令，4 张被排除**：

| 场景 | 输入 | 清算结果 |
|---|---|---|
| **三方环形债务** | A→B、B→C、C→A 各 CNY 1,000,000；另有 A→B CNY 250,000 | 3 张环票净头寸全 0 全额互抵；全组收敛为 **1 笔** B→A CNY 250,000 |
| **跨币种环 + 尾差** | D→E EUR 200.01 @1.085；E→F USD 300；F→D USD 217.01 | EUR 票折算 217.01085→**217.01**，逐笔尾差 **−0.00085**（D）；承担方 F 归集 **+0.00085**，尾差合计 0；现金仅 **1 笔** F→E USD 82.99 |
| **质押 / 争议** | A→B CNY 80,000（质押）、B→A CNY 80,000（争议） | 两张均排除（`PLEDGED` / `DISPUTED`），**原债务保留、金额不变** |
| **不允许互抵** | G→H、H→G 各 HKD 50,000，无协议 | 两张均 `NO_AGREEMENT`，不互抵、不出现在任何清算组 |

守恒实测（集成测试与 HTTP 验收均断言）：

- 每组各法人净头寸合计 = **0**；逐主体「毛应收−毛应付」与净头寸完全一致；
- 每张发票 `互抵额 + 付款额 = 折算额`；
- 跨币种尾差行合计 = **0**，且收付指令中**不含**尾差金额；
- 确认后：环内 7 张 `CLEARED`，质押/争议/无协议 4 张仍 `ACTIVE`；已确认批次不可二次确认（422）。

`scripts/acceptance.sh` 最终输出：

```
PASS trial: 三方环缩减为 1 笔, 跨币种 1 笔, 尾差零和
PASS confirm: CFM-... CONFIRMED
PASS 原债务保留: R-3001,R-3002,R-4001,R-4002
ALL ACCEPTANCE CHECKS PASSED
```
