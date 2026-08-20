# Day 9 — Electron 便携封装与 Windows 运行闭环（D9-T01～D9-T05）

> 性质：Day9 实施证据（D9-T01～D9-T05 实施完成，待技术负责人 Code Review）。
> Base：`6a2965a`（Day8 COMPLETE merge）+ Cloud LLM Closure `b6e3334`；实施 commit：
> `d377a12`（D9-T01）、`4269ab8`（D9-T02）、`bf55e54`（D9-T03）、`d61135b`（D9-T04）、`<D9-T05 pending>`（D9-T05）。
> 冻结边界：Day1-Day8 业务包零修改（仅新增 `com.supplymind.desktop.*`）；Cloud LLM 链 / Agent Tool Boundary / EvidencePack / ReportStore / DEC 规则未触碰；API Key 只经环境变量进入 Java 子进程，不进入制品、日志、renderer。

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

- D9-T01～D9-T05 = 实施完成；`TaskExecutionStatus=REVIEW_PENDING`（待 Code Review）。
- 未 merge main；未 tag；Day1-8/Cloud LLM 结论未改变。
