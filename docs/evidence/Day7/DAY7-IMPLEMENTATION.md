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
