# Day 7 — Vue Dashboard 实现（2026-08-16）

> 性质：Day7 实施证据（D7-T01～D7-T04 实施完成，`REVIEW_PENDING`，待 Code Review）。
> Base：`74bb8ce`（Day6 COMPLETE merge）；实施 commit=`<pending>`（提交后回填）。
> 冻结边界：Day6 = Agent + LLM（零修改）；Day7 = Vue Dashboard 展示层。禁止重新设计 Agent/EvidencePack/LLM/Tool/Storage/Calculation。

## 架构（冻结）

```
Vue3 (frontend/) --HTTP /api/dashboard--> DashboardController --> DashboardService
                                                                    |--> PublishedQueryService（已发布值/最新值/stale）
                                                                    |--> HistoryQueryService（daily/aggregate 查询）
                                                                    |--> ConfigManagementService（默认监测项/路线/来源）
                                                                    |--> manifest-verified warning 文件（只读）
```

- Controller 不读任何业务文件；所有查询/聚合/质量判断在 Java 完成。
- Vue 只渲染后端返回的精确 BigDecimal 字符串（DEC-008：`new BigDecimal(String)` 全链路，前端零计算）。
- 未验证数值不显示（`PublishedQueryService` 仅 PUBLISHED+VERIFIED 类可见；无数据 → `NO_DATA`，不伪造）。

## Backend API（/api/dashboard，只读）

| Endpoint | 返回 | 说明 |
|---|---|---|
| `GET /api/dashboard/overview` | `{mode, items[], warnings[]}` | 每项卡片：最新值/业务日期/单位/来源/Provider/路线/校验版本/stale/预警摘要 |
| `GET /api/dashboard/history?itemId&from&to` | `{itemId, fromDate, toDate, points[], missingRefs[], corruptRefs[], dataThrough}` | daily 序列（精确字符串），缺失/损坏显式报告 |
| `GET /api/dashboard/metrics?itemId&grain&fromYear&toYear` | `{itemId, grain, fromYear, toYear, rows[], missingRefs[], corruptRefs[]}` | month/quarter/halfyear/year 聚合 |
| `GET /api/dashboard/quality?itemId&from&to` | `{itemId, latestStatus, rows[], warnings[], evidenceMissingRefs[], evidenceCorruptRefs[]}` | 校验/预警/证据状态 |
| `GET /api/dashboard/sources` | `{mode, items[], manualEntry, importEntry}` | 来源与三层路线；manual/import HTTP 入口=`PENDING`（Day8 契约，不伪造完成） |

- 错误契约：非法参数 → `400 {status:REJECTED,message}`；服务不可用 → `500 {status:UNAVAILABLE,message}`；无堆栈泄漏。
- DTO：`com.supplymind.dashboard.api.DashboardV1` 纯展示 DTO，JSON 字段名冻结（DTO contract test 断言）。

## Frontend（frontend/）

- Vue3 + Vite + TypeScript + Vue Router（hash 模式，Day9 Electron 便携）+ Axios（`/api` dev proxy → 127.0.0.1:8080）。
- 页面：`DashboardView`（卡片网格 + DEMO 全页水印 + 错误横幅）、`HistoryView`（日期范围 + 日/月/季/半年/年切换 + SVG 趋势（只画后端 points，缺失不插值）+ 精确表格）、`QualityView`（校验表 + 预警表 + 证据缺失/损坏）、`SourcesView`（路线表 + PENDING 录入入口）。
- 组件：`ValueCard`、`StatusBadge`、`TrendChart`（SVG，无前端聚合）。
- 精确展示：所有业务值为后端字符串直出（`value as string`，无 parseFloat/Number 参与展示）。
- 测试：`src/views/__tests__/pages.spec.ts` 6 项（页面启动、后端字符串渲染、DEMO 水印、API 失败不白屏、缺失文件提示、预警渲染）——`vi.mock` API 模块（API mock 验证）。

## 测试结果（真实执行）

| 套件 | 结果 |
|---|---|
| `DashboardServiceTest` | 6/6 PASS（overview/history/metrics/quality/sources/缺失区间诚实） |
| `DashboardControllerTest` | 4/4 PASS（200 契约、非法日期 400 REJECTED、structured 错误） |
| `DashboardDtoContractTest` | 3/3 PASS（字段名冻结、18 位小数字符串原样过线、值保持字符串） |
| 前端 `npm run test`（vitest） | 6/6 PASS（Test Files 1 passed） |
| 前端 `npm run build`（vue-tsc + vite build） | PASS（dist 产出） |
| 后端全量 `.\mvnw.cmd clean test` | 108 suites / 548 tests / 0 failures / 0 errors / 8 skipped |

- Day1-Day6 代码零修改（全部改动在 `backend/src/main/java/com/supplymind/dashboard/**` 与 `frontend/**`）；旧测试 0 failures/0 errors（含 8dc0c07 独立攻击 20/20 在内）。
- skipped=8 与 Day5/Day6 基线逐项相同（真实联网/raw 门禁 + AT-TIME-003/004 D10），未新增 skip。

## 状态

- D7-T01～D7-T04 = `TaskExecutionStatus=REVIEW_PENDING`（实施完成；Code Review 通过后由技术负责人改 `DONE`）。
- Day6 COMPLETE 保持；Day6 代码零修改；未 merge main；未 tag；Day6 状态保持 REVIEW_PENDING（Day7 实施不改变 Day6 结论）。

## 攻击修复（Terra Finding，2026-08-16，commit 见 05-PROGRESS-LEDGER）

1. **TrendChart 业务值零计算**：前端不进行任何业务值计算（均值/坐标/聚合/质量状态推导全部在 Java）；`DashboardService.chartOf` 计算全部展示坐标（固定 640x160、min/max 缩放），`Chart{width,height,points[{label,x,y}]}` 随 DTO 下发，Vue 只渲染 polyline 字符串。注意：前端代码中 `Number()` 仅用于把用户选择的日期范围年份提取为查询参数（fromYear/toYear 传参），不参与任何业务值计算——文档口径为"业务值零计算"，不声称"Number 零出现"。
2. **DTO 不暴露内部 csv 路径**：`missingRefs/corruptRefs`/`evidenceMissingRefs/evidenceCorruptRefs` 全部移除，改为业务引用 `EvidenceIssue{periods[], status(MISSING|CORRUPT), reason}`（daily ref → `YYYY-MM`、aggregate ref → `YYYY grain`）；契约测试断言响应体永不包含 `processed/`。
3. **API 错误统一**：DashboardController 单一 `okOrRejected`——任何失败（非法日期/非法 grain/数据不可用）都是 `400 {status:REJECTED, message}`；`500` 从 dashboard API 中移除（MockMvc 测试断言非法参数 400 且 message 非空）。
4. **DashboardService 不再直读 warning 文件**：改为注入 `WarningService`；`WarningService` 新增只读方法 `findRecent(itemId, lookbackMonths)`（manifest 校验扫描，复用既有 DataRoot/ManifestVerifier，不触碰既有方法，Day1-Day6 行为零改动）；DashboardService 的全部 warning 读取走该 service。
5. **真实 MockMvc API 测试**：新增 `DashboardApiMockMvcTest`（6 项）——standalone MockMvc + 真实 DashboardService（持久化 fixture）：overview 200 契约、history chart/evidenceIssues、缺失期间业务引用、非法日期/grain → 400 REJECTED（非 500）、quality/sources 200。

## API 契约修复（Terra Retest Finding，2026-08-16，commit 见 05-PROGRESS-LEDGER）

1. **未知 itemId fail-closed**：`DashboardService.requireKnownItem`——history/metrics/quality 的 itemId 必须存在于活动配置，否则 `IllegalArgumentException("unknown itemId")` → HTTP 400 `{status:"REJECTED", message:"unknown itemId"}`（绝不以 200+空数据掩盖）。
2. **非法范围拒绝**：`requireRange`——`from > to` → 400 `message:"from must not be after to"`；超过 `MAX_RANGE_DAYS=3660`（10 年，与 Agent 工具同一语义）→ 400 `message:"date range too large (max 3660 days)"`；metrics 增加 `fromYear>toYear` 与 `MAX_YEAR_SPAN=10` 校验。
3. **Controller 错误消息受控透传**：`okOrRejected` 对 `IllegalArgumentException` 透传受控服务端消息（unknown itemId / 范围 / 日期格式），其余失败统一 400 通用消息；仍无 500、无堆栈泄漏。
4. **新增 MockMvc 测试**（+4，共 10 项）：unknown itemId → 400 精确 message（history/quality/metrics 三端点）、from>to → 400、oversized range → 400 精确 message、valid request → 200。

保持：DEC-008（值全链路字符串）、Day1-Day6 契约（零修改）、WarningService 边界（findRecent 只读，不触碰既有方法）、前端零计算（本 commit 无前端改动）。

回归（CURRENT）：后端全量 `.\mvnw.cmd clean test` = **110 suites / 563 tests / 0 failures / 0 errors / 8 skipped**（+5 正式 MVC contract）；前端 `npm run test` 6/6、`npm run build` PASS；DEC-008 保持（值全链路字符串，坐标仅展示几何）。

## 最终审查修复（Sol Findings，2026-08-16，commit 见 05-PROGRESS-LEDGER）

1. **M1 D7-T04 Source Management**：SourcesView 增加 Manual Entry 表单（itemId/来源/业务日期/值/单位 → Submit → PENDING 状态与受理提示）、文件导入入口（file input → 本地预览（行号/单元格）→ 逐行错误展示（缺列/空字段表单校验）→ Submit → PENDING）。不实现真实写入，保持 Day8 写入边界（提交仅记录受理状态）。
2. **M2 History/Metric Cross Year**：HistoryView 聚合模式使用**用户选择范围派生的真实 fromYear/toYear**（不再固定 2026）；daily 与聚合两个面板均展示后端返回的 `evidenceIssues`（业务期间 + status + reason）；from>to 前端即拒绝。
3. **M3 Dashboard Contract**：DTO 补充 `completeness`（QualityRow，后端按 (expected−missing)/expected 12 位 HALF_UP 计算，与 warning 数据质量规则同语义）与 `aggregateSummary`（ItemCard，后端取当月最新 month 聚合行）；`stale` 不再硬编码——按 DEC-051 同一规则（业务日期早于参考日期 30 天）由后端真实计算。
4. **M4 Error Contract**：新增 `DashboardApiAdvice`（@RestControllerAdvice 限定 dashboard 包）统一处理：缺少参数 → 400 `required parameter 'x' is missing`；类型转换失败（非数字 year）→ 400 `parameter 'x' has an invalid value`；非法日期 → 400；其余失败 → 400 通用消息；全部 `{status:"REJECTED", message}`，无 500、无框架默认错误体、无堆栈泄漏。为承载正式 servlet MVC，pom 新增 `spring-boot-starter-web`（Day7 Web 形态基础，无数据库，不触碰任何业务 bean/语义）。
5. **M5 API Tests**：新增**正式 MVC contract 测试** `DashboardMvcContractTest`（@WebMvcTest + 完整 DispatcherServlet，5 项）：missing param → 400、invalid type → 400、invalid range → 400 + 精确 message、unknown itemId → 400、valid request → 200（overview/history/metrics 契约体）。standalone MockMvc 测试保留为补充。
6. **M6 WarningService**：`findRecent` 改为**逐文件 try/catch**——单个损坏 warning 文件 `continue` 跳过，绝不中断同月其余文件扫描；目录列举失败也只跳过该月。
7. **M7 Evidence/docs**：CURRENT regression 统一为 110 suites/563 tests/0 failures/0 errors/8 skipped；旧数字（109/558、108/548 等）一律 HISTORICAL（见下表）。

## 最终审查修复 V2（Sol V2 Findings，2026-08-16，commit 见 05-PROGRESS-LEDGER）

1. **M1 Source Management 真实闭环**：新增后端 pending API——`POST /api/dashboard/manual`（校验已知 itemId/必填 businessDate/value → 结构化 `{status:"PENDING", message, itemId, businessDate, value}`，不持久化，Day8 写入边界）；`POST /api/dashboard/import`（multipart 上传，**后端真实解析 CSV**（逐行 preview + 行级错误，真实行号）；xlsx/xls 与不支持类型 → 明确 `REJECTED` + message，前端不再 `file.text()` 假解析）。前端 SourcesView 只展示后端返回的 status/message/previewRows/rowErrors。
2. **M3 Dashboard Contract**：`ItemCard` 增加 `completeness`（最新 daily 行 (expected−missing)/expected 12 位 HALF_UP）；`aggregateSummary` 改为**跨年选择最新有效聚合**（当前年→前两年，跳过空年）；`stale` 保持 DEC-051 真实计算。新增行为测试：completeness、stale boundary（30 天前不 stale/31 天前 stale）、aggregate latest selection（2026/2025 空 → 取 2024）。
3. **M2 Cross Year**：新增真实调用测试——metrics(2024..2026) 断言 fromYear/toYear 原样传递 + 两年聚合行均返回；history 跨年 points；前端测试断言聚合年份由用户选择范围派生。
4. **M6 WarningService**：新增测试——合法 warning 与损坏文件同目录共存时 `findRecent` 跳过坏文件、返回合法记录。
5. **新增测试**：manual submit（MockMvc PENDING + 缺 value 400）、import submit（CSV 真实解析 + xlsx REJECTED 精确 message）、前端 manual/import/xlsx 提交测试（页面测试 10 项）。

| 回归快照 | suites | tests | 状态 |
|---|---|---|---|
| 最终审查修复（a4006c4） | 110 | 563 | HISTORICAL |
| **最终审查修复 V2（本 commit）** | **110** | **573** | **CURRENT** |

## M1 契约冻结（Terra Final Attack Retest，2026-08-16，commit 见 05-PROGRESS-LEDGER）

1. **Manual API 契约冻结**：`POST /api/dashboard/manual` 必须校验 `source` 与 `unit` 非空——缺失 → 400 `{status:"REJECTED", message:"source is required"|"unit is required"}`；完整请求返回 `{status:"PENDING", itemId, source, unit, businessDate, value, message}`（source/unit 为冻结字段）。
2. **Import API 契约冻结**：CSV preview 采用冻结 5 列 schema（itemId, source, businessDate, value, unit）；source/unit 缺失 → 进入 `rowErrors`（"来源为空"/"单位为空"），绝不进入 PENDING preview；xlsx 保持 REJECTED（不引入新解析）。
3. **新增测试**：manual missing source → 400、manual missing unit → 400、manual valid → PENDING 且含 source/unit（service + MockMvc 双覆盖）；CSV 缺 source/unit → row error（"来源为空"/"单位为空"精确断言）、CSV valid → preview 成功（5 列 cells 断言）；前端 manual/import 提交测试更新（后端字段契约）。

保持：Day8 真实写入边界（仍只受理 PENDING）、DEC-008（值全链路字符串）、Day1-Day6 契约零修改、测试断言未降低。

回归（HISTORICAL，1b83410 时点）：110 suites / 574 tests / 0 failures / 0 errors / 8 skipped。

## Final Stage Review 修复（2026-08-16，commit 见 05-PROGRESS-LEDGER）

1. **MAJOR 1 D7-T04 真实 PENDING（不再自证）**：Manual/Import 端点改为**复用既有真实边界**——`POST /api/dashboard/manual` 走 `ManualMaterialIntakeService`（immutable raw + RECEIVED/PARSED+PENDING lifecycle timeline 真实持久化，响应携带真实 runId/rawRef/timelineRef 证据；unit 必须匹配配置 item 单位，仅 Manual 路由标的可提交）；`POST /api/dashboard/import` 走 `LocalImportService`（CSV **与 XLSX** 均真实解析（POI），冻结 9 列模板头，接受行真实持久化为 RECEIVED+PENDING 证据，行级错误逐行报告，文件级失败明确 REJECTED）。删除自造的"假 PENDING"与自定义 CSV 解析。
2. **MAJOR 2 模板下载 + Synthetic 演示入口**：`GET /api/dashboard/import/template`（冻结 LocalImport 模板头下载，Content-Disposition attachment）；`POST /api/dashboard/synthetic-demo`（真实 `SyntheticDemoDataProvider` 确定性生成，响应 `DEMO_GENERATED`，明确"不持久化到正式存储、非路线候选"）；SourcesView 增加模板下载链接与 Synthetic 演示按钮。
3. **MAJOR 3 D7-T02 完整率展示**：前端 `ItemCard` 类型补 `completeness` 字段，`ValueCard` 渲染"完整率"（后端计算字符串直出）。
4. **MAJOR 4 docs 口径统一**：CURRENT regression=**110 suites/578 tests/0 failures/0 errors/8 skipped**；前端 **11/11 PASS**；旧快照一律 HISTORICAL。

| 回归快照 | suites | tests | 状态 |
|---|---|---|---|
| Day6 final（bc6f61a） | 105 | 535 | HISTORICAL |
| Day7 实施（2ea6a4b） | 108 | 548 | HISTORICAL |
| 攻击修复（5f1491c） | 109 | 554 | HISTORICAL |
| API 契约修复（3fd1d35） | 110 | 558 | HISTORICAL |
| 最终审查修复（a4006c4） | 110 | 563 | HISTORICAL |
| 最终审查修复 V2（d2b0965） | 110 | 573 | HISTORICAL |
| M1 契约冻结（1b83410） | 110 | 574 | HISTORICAL |
| **Final Stage Review 修复（本 commit）** | **110** | **578** | **CURRENT** |

保持：Day1-Day6 语义与契约零修改、DEC-060 未改、测试断言未降低、前端业务值零计算。
