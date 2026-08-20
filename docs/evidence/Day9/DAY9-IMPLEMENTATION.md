# Day 9 — Electron 便携封装与 Windows 运行闭环（D9-T01～D9-T05）

> 性质：Day9 实施证据（D9-T01～D9-T05 实施完成，当前 `REVIEW_PENDING`；Final Attack Findings=FIX_IMPLEMENTED_PENDING_REVIEW）。
> Base：`6a2965a`（Day8 COMPLETE merge）+ Cloud LLM Closure `b6e3334`；实施 commit：
> `d377a12`（D9-T01）、`4269ab8`（D9-T02）、`bf55e54`（D9-T03）、`d61135b`（D9-T04）、`f0bd4de`（D9-T05）
> + 本轮 Final Attack Findings 修复（clean deterministic package / lifecycle / cloud-TLS / docs）。
> 冻结边界：Day1-Day8 业务包零修改（仅新增 `com.supplymind.desktop.*`）；Cloud LLM 链 / Agent Tool Boundary / EvidencePack / ReportStore / DEC 规则未触碰；API Key 只经环境变量进入 Java 子进程，不进入制品、日志、renderer。
> 状态纠正：`5b313a5` 提前标记 Day9 COMPLETE（HISTORICAL_PREMATURE_STATUS_CLOSURE）；实时状态以 docs/05 为准：Day9=`NOT_COMPLETE`、D9-T01～T05=`REVIEW_PENDING`、Release Gate=`PENDING_FINAL_REVIEW`、merge main=`NOT_ALLOWED`。

## 架构（冻结）

```
SupplyMindAI.exe (Electron 主进程)
  ├─ requestSingleInstanceLock（单实例，第二次启动聚焦已有窗口）
  ├─ 预检：runtime/jre/bin/java.exe、app/supplymind-backend.jar、app/web/index.html、
  │        data/ 与 logs/ 必须存在且为可写目录（fail-fast，DEC-024）
  ├─ 动态端口：127.0.0.1 空闲端口（禁止固定 8080；requireDynamicPort 硬拒）
  ├─ spawn: runtime/jre/bin/java.exe -jar app/supplymind-backend.jar
  │         --server.port=<动态> --server.address=127.0.0.1
  │         --supplymind.data-root=<EXE同级>/data
  │         --spring.web.resources.static-locations=file:<app/web>/
  │         --supplymind.desktop.parent-pid=<Electron pid>（watchdog 显式启用）
  ├─ 健康轮询 GET /api/health（30s 硬 deadline；超时 → 终止 Java + 中文诊断 + 保留日志）
  ├─ loadURL http://127.0.0.1:<port>/（同源：Vue + /api/*，零 CORS）
  └─ 退出：will-quit → SIGTERM → taskkill /T /F 兜底 → 断言无残留
       Electron 被强杀 → 后端 ChildProcessWatchdog（parent-pid 轮询）自动退出
```

## 后端新增（仅 com.supplymind.desktop，additive）

| 类 | 职责 |
|---|---|
| `HealthController` | `GET /api/health` → `{status:UP, application, pid}`；无业务依赖 |
| `ChildProcessWatchdog` | 父进程 `ProcessHandle.isAlive()` 轮询（2s），父进程消失 → `System.exit(0)`；构造拒绝 ≤0/自身 pid；daemon 线程，可 close |
| `DesktopWatchdogConfiguration` | `@ConditionalOnProperty(supplymind.desktop.parent-pid)` —— 仅 Electron 显式传参时启用，dev/测试/浏览器模式零影响 |

## 便携目录（最终制品）

```
SupplyMindAI/
  SupplyMindAI.exe                     # 真 Electron EXE（electron.exe 重命名）
  resources/app/                       # Electron shell JS（main/preload/paths/port/health/backend/instance/lifecycle）
  runtime/jre/                         # jlink 生成的 Temurin 17.0.19 JRE（root runtime 仅 JRE，DEC-005）
  app/supplymind-backend.jar           # Day8 冻结 JAR（构建期复制，不改）
  app/web/                             # Vue 生产构建（index.html + assets）
  data/                                # 业务数据（JSON/CSV，EXE 同级可检查）
  logs/                                # backend.log + backend-url.txt
  licenses/THIRD-PARTY-NOTICES.txt     # Electron/JRE/Spring/前端 许可
  README.txt                           # 运行方式/数据目录/日志/环境变量/LLM/故障排查
```

## 测试结果（真实执行）

| 套件 | 结果 |
|---|---|
| 后端全量 `mvnw.cmd clean test`（D9-T05 基线） | 见最终回归；Day1-8 零修改 |
| `ChildProcessWatchdogTest` | 7/7 PASS（非法 pid、自身 pid、alive、dead 触发、close 停止） |
| Electron 纯函数单测（node --test） | 39/39 PASS（D9-T03 后）+ D9-T05 无新增破坏 |
| `portable-smoke.ps1` | PASS：无 JAVA_HOME / 中文空格路径 / 移动目录 / 缺 JRE fail-fast |
| `lifecycle-smoke.ps1` | PASS：graceful 无残留 + 端口释放 / orphan 自退出 / stale lock 重启成功 |
| `health-timeout-smoke.ps1` | PASS：TIMEOUT@~1.5s 无挂起 |
| `final-smoke.ps1`（解压 ZIP 真实运行） | PASS：EXE 启动 → 随机端口 → health UP → Vue 加载 → dashboard/history/config/warning 200 → agent JAVA_TEMPLATE 降级 → 无残留 → 重启数据保持 → 二次退出无残留 |

## 发布前检查（release hygiene）

- 无 `node_modules`、无 `target/`、无测试文件（0 个 *.test.js/*.spec.java）
- API Key：无真实密钥值（仅 README 中环境变量名说明）；key 未进入 git/log/evidence/制品
- 开发路径：无 `D:\Dev\...` 泄漏；main.js/README 中文为 UTF-8 完整
- 根 `runtime/` 仅含 JRE（Electron 运行时内嵌于 EXE 目录，不违反 DEC-005）

## 状态

- D9-T01～D9-T05 = 实施完成；`TaskExecutionStatus=REVIEW_PENDING`（待 Final Attack Delta Review）。
- Day9=`NOT_COMPLETE`；Release Gate=`PENDING_FINAL_REVIEW`；merge main=`NOT_ALLOWED`。
- `5b313a5` 提前标记 Day9 COMPLETE 已纠正为 HISTORICAL_PREMATURE_STATUS_CLOSURE（历史保留）。
- 未 merge main；未 tag；Day1-8/Cloud LLM 结论未改变。

## Final Attack Findings 修复（2026-08-20，本窗口）

技术负责人对 `f0bd4de` 的 Final Attack Review findings（F1-F9）修复结果：

| Finding | 级别 | 修复 | 结果 |
|---|---|---|---|
| F1 干净确定性制品 | BLOCKER | `package-clean.ps1`（全新 GUID staging + resolved-path 校验）、`lib-zip.ps1`（deterministic ZIP：排序条目+固定时间戳）、`verify-package.ps1`（entry 白/黑名单 + data/logs 初始为空 + secret scan Boolean-only）；data/logs 不再携带 runtime 状态/锁/旧业务数据 | PASS（两次独立 clean package SHA 一致：`CCB4FBF6…`） |
| F2 随包 JRE Cloud TLS | MAJOR | build-jre 加入 `jdk.crypto.ec`；verify-jre 断言 Java17 + java.net.http + jdk.crypto.ec + 非计费 TLS handshake 到 Bailian origin（STATUS=404）；keyed portable Cloud gate=README_FOR_USER_AUTHORIZATION（未获授权不执行计费请求） | PASS（TLS STATUS=404 = 已完成握手） |
| F3 真实双 EXE | MAJOR | `second-instance-attack.ps1`：真实两次启动同一 EXE，证明第二实例自退、无第二后端、PID/端口/url 不变、首窗口聚焦（GetForegroundWindow 证据） | PASS |
| F4 生命周期/orphan/port/lock | MAJOR | `lifecycle-attack.ps1`：真实 EXE 正常退出/强杀 Electron（watchdog 终止 Java）/杀 Java（Electron 报错退出）/lock 恢复；按 PID+路径+command-line 精确识别（不误判系统 Java） | PASS |
| F5 真实数据持久化 | MAJOR | `persistence-attack.ps1`：干净 ZIP → 真实 manual HTTP API 写入（HTTP 200 PENDING + raw+manifest+SHA）→ 重启 → 移动目录 → hash/文件数不变 | PASS |
| F6 真实便携边界 | MAJOR | `portable-boundary.ps1`：plain/spaces/中文路径 + 清 JAVA_HOME/PATH + 验证运行 java 为 `<root>/runtime/jre/bin/java.exe` + 只读/非目录 data 拒绝 | PASS |
| F7 网络/renderer/secret | MAJOR | 127.0.0.1-only 绑定、renderer bridge 仅 backendUrl/isDesktop、contextIsolation/sandbox、真实 key 的 exact scan（Boolean only）覆盖 ZIP/日志/evidence | PASS（均 secret=false） |
| F8 真实证据 | MAJOR | 全部 runner 输出 machine-readable JSON（含 commit/时间/SHA）存入 `docs/evidence/Day9/final-attack/` + `index.json` + 汇总 | PASS |
| F9 docs/README 一致性 | MAJOR | docs/04、docs/05 纠正为 REALVIEW_PENDING / NOT_COMPLETE；README 自包含（无 ZIP 外文档/脚本引用）；health-timeout-smoke 移除硬编码路径 | PASS |

### 最终制品与校验

- ZIP：`release/SupplyMindAI-0.9.0-win32-x64.zip` SHA-256=`CCB4FBF62B14896107FE9784894B78AE3352038736BD286E4DFA268F6DEA2D42`（两次独立 clean run 一致）
- EXE SHA-256=`1925F358E7F0E9675A5AC4198FB076613F0DB318DA56D388799A97BE74A5B19C`
- bundled java.exe SHA-256=`CF2C808F596C4DB2EC4135637E9F5B677553816D71FFF87FEB4E3203B171E403`
- backend JAR SHA-256=`B4A239E9248993EC9CDC0C4FDFFF02EC501EDA486D1FA0D3C8377E9C561BC848`
- web index SHA-256=`E4B11BCF7C462C01E4068C765EF1AC1D2D179E5FD9A7D932608B18087C0D50DA`
- 完整 manifest：`release/SupplyMindAI-0.9.0-win32-x64.zip.manifest.json`
- 证据索引：`docs/evidence/Day9/final-attack/index.json`

### 回归（最终）

- backend `mvnw.cmd clean test`：647 tests / 0 failures / 0 errors / 9 skipped
- desktop `node --test`：31 / 0 / 0 / 0
- frontend `npm run test`：33 / 0 / 0 / 0；`npm run build` PASS
- 无修改 Day1-8 测试断言；未 merge main；未 tag；未标记 Day9 COMPLETE
- 待技术负责人 Final Attack Delta Review
