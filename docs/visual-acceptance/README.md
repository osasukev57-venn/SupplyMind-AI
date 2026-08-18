# 视觉验收：UI 产品化与治理信息隔离（D8 / T10）

日期：2026-08-18
环境：真实应用（Spring Boot backend :8080 + Vite dev :5173，hash 路由）

## 验收方法

- 前端测试：`frontend npm run test` → 5 文件 / 33 用例全部通过（含 governance.spec 治理隔离断言）
- 真实浏览器（Playwright）逐页渲染 7 个页面，抓取页面可见文本正则扫描治理词，并生成截图
- 截图来自真实应用（后端真实数据），非静态 HTML

## 页面文本治理扫描结果

扫描模式：`D\d+-T\d+` `Day\d+` `DEC-\d+` `EXT-\d+` `AT-[A-Z0-9-]+` `\bP[012]\b` `sidecar` `capability` `前端不计算` `后端编排` `Java确定性规则` `official_web` `FALLBACK_MANUAL` `AWAITING_MANUAL_INPUT` `PRIMARY` `providerType` `routeDecision`

| 页面 | 标题 | 治理词命中 |
|---|---|---|
| dashboard | 总览 | 无 |
| history | 历史趋势 | 无 |
| quality | 数据质量 | 无 |
| sources | 来源与录入 | 无 |
| config | 动态配置 | 无 |
| warning | 预警 | 无 |
| agent | Agent 工作台 | 无 |

修复记录：ValueCard / ConfigView / SourcesView 的"采集路线"与"降级原因"原样渲染后端枚举（FALLBACK_MANUAL、MANUAL_FALLBACK），已改经 `routeLabel` / `fallbackReasonLabel` 展示层映射。

## 汇报清单

【Visible Governance Leakage】CLEARED
- 全页面文本扫描（Playwright 真实渲染）零命中；governance.spec 3 用例通过

【Internal Enum Presentation】PASS
- official_web → 官方网站；PRIMARY → 主要路线；FALLBACK_MANUAL → 人工补录；AWAITING_MANUAL_INPUT → 等待人工录入
- fallbackReason（MANUAL_FALLBACK 等）→ 人工补录等中文原因
- 枚举仅作为表单下拉 `value` 保留（不显示给用户），技术细节不可见

【Product Copy】PASS
- 动态配置页描述："集中管理监测范围、采集路线和历史回填。配置生效后，监测面板将自动更新，已有历史数据继续保留。"
- Agent 页描述："基于已验证的数据和只读分析工具生成可追溯报告。智能分析服务不可用时，系统会自动切换到本地可信报告。"
- 预警页描述：不包含任何 D8-T02 / EXT-07/08 / DEC-061 / sidecar 等字样
- 错误文案："服务暂时不可用，请稍后重试" 等产品化语言，无"后端不可用/capability 校验失败"

【Navigation IA】PASS
- 左侧导航三分组：监测（总览/历史趋势/数据质量）、数据管理（来源与录入/动态配置）、运营分析（预警/Agent 工作台）
- 顶栏仅保留页面标题与状态（无冗余操作）
- 768px 折叠为横向滚动导航条（见 dashboard-768.png）

【Visual Hierarchy】PASS
- Page Header（eyebrow + 标题 + 描述 + 主操作）→ Summary Strip（配置版本/启用项/运行任务/运行状态）→ 主工作区 → 次级区块
- 深蓝品牌色 + 青蓝强调 + 状态色（绿/琥珀/红/紫/灰），分层表面，非白色 panel 平铺

【Config Page Redesign】PASS
- 监测项列表第一层名称+编号、第二层状态+来源、第三层接入方式+路线，操作列（停用/启用/替换）
- 新增/替换表单按需展开（Primary Button 入口），回填任务、配置历史、来源能力分区展示

【Status Semantics】PASS
- VERIFIED/FORMAL/SUCCEEDED/ENABLED → 绿色；VERIFIED_WITH_NOTICE/STALE/PARTIAL_SUCCESS → 琥珀
- PENDING/WAITING/AWAITING_MANUAL_INPUT/RUNNING → 蓝色中性；FAILED/REJECTED/CONFLICT/DISABLED → 红色
- UNKNOWN → 灰色；DEMO → 紫色并标注"演示"
- 实现见 StatusBadge.vue badgeTone/badgeLabel

【Before/After Evidence】
BEFORE 依据：需求评审记录（见 04-DEVELOPMENT-TASKS.md 与 T10 需求第 4/5 节描述）——顶栏七入口横向平铺、全白色 panel 堆叠、表格式数据库预览、原始枚举直接展示。仓库无旧界面截图存档。
AFTER 截图清单（docs/visual-acceptance/，均来自真实应用）：

| 页面 | 1440×900 light | 1440×900 dark | 768px responsive |
|---|---|---|---|
| dashboard | dashboard-1440-light.png | dashboard-1440-dark.png | dashboard-768.png |
| history | history-1440-light.png | history-1440-dark.png | history-768.png |
| quality | quality-1440-light.png | quality-1440-dark.png | quality-768.png |
| sources | sources-1440-light.png | sources-1440-dark.png | sources-768.png |
| config | config-1440-light.png | config-1440-dark.png | config-768.png |
| warning | warning-1440-light.png | warning-1440-dark.png | warning-768.png |
| agent | agent-1440-light.png | agent-1440-dark.png | agent-768.png |

## 保留约束确认

1. 页面不再出现开发任务/决策/验收编号：已确认
2. 内部枚举已转为中文显示名：已确认
3. 主操作（新增监测项、提交录入、查询等）为高对比 Primary 按钮：已确认
4. 中文为主，英文仅用于业务数据（itemId、单位符号等）：已确认
5. DEC-008 前端零业务计算：本次未引入任何 parseFloat/Number/toFixed，labels.ts 为纯展示映射
6. 所有现有业务字段与功能仍可访问：33 个前端用例 + 真实页面渲染均通过，未删除任何功能
