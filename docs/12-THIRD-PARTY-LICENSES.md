# SupplyMind AI — 第三方许可

> 文档编号：SMA-LIC-001
> 适用版本：P0 便携发布
> 最后更新：2026-08-20
> 随包副本：`SupplyMindAI/licenses/THIRD-PARTY-NOTICES.txt`

## 1. 内置运行时

| 组件 | 版本 | 许可 | 说明 |
|---|---|---|---|
| Eclipse Temurin JDK/JRE | 17.0.19 | GPLv2 with Classpath Exception | 内置 `runtime/jre`，用户无需安装 Java |
| Electron | 33 | MIT | 桌面壳（SupplyMindAI.exe） |
| Chromium（Electron 内置） | Electron 33 对应版本 | BSD 系（见 `LICENSES.chromium.html`） | Web 渲染 |

## 2. 后端（`app/supplymind-backend.jar`，Spring Boot 3.5.15 / Java 17）

| 组件 | 版本 | 许可 |
|---|---|---|
| Spring Boot / Spring Framework | 3.5.15 | Apache License 2.0 |
| Spring AI | 1.1.8 | Apache License 2.0 |
| Apache Commons CSV | 1.11.0 | Apache License 2.0 |
| Apache POI | 5.2.5 | Apache License 2.0 |
| SLF4J / Logback | Spring Boot 管理版本 | MIT / EPL 1.0 |
| Jackson（Spring Boot 管理） | 2.x | Apache License 2.0 |

## 3. 前端（`app/web`）

| 组件 | 版本 | 许可 |
|---|---|---|
| Vue 3 | 3.x | MIT |
| Vue Router | 4.x | MIT |
| Axios | 1.x | MIT |
| Vite | 5.x（构建工具，不随包） | MIT |
| TypeScript | 5.x（构建工具，不随包） | Apache License 2.0 |
| Vitest | 2.x（测试工具，不随包） | MIT |

## 4. 数据来源许可/合规

- 中国人民银行汇率：公开官方公告页（公开访问、授权发布渠道），按 DEC-015/037 合规获取，不绕过任何访问控制。
- SMM / Asian Metal：P0 未获得会员授权，**不执行任何自动采集**；对应能力标记为 `N/A_APPROVED_FALLBACK`，经项目方批准以 Manual 路线满足 P0 接入（SUP-08）。
- 免费公开信源（如启用）：必须记录并展示真实网站名称、URL/引用，不冒充指定商业源。

## 5. 说明

- 以上为运行时随包组件；开发期工具（Maven、Node、构建插件）不属于最终交付。
- 完整 license 文本随包分发于 `licenses/`；Electron/Chromium 条款见包内 `LICENSES.chromium.html`。
- 本项目源码许可以仓库 LICENSE 声明为准（当前 P0 为内部交付）。
