# SupplyMind AI — Windows 部署手册

> 文档编号：SMA-DEP-001
> 适用版本：`SupplyMindAI-<版本>-win32-x64.zip`（P0 便携发布形态）
> 最后更新：2026-08-21

## 1. 部署前置条件

| 项 | 要求 |
|---|---|
| 操作系统 | Windows 10 x64 或更高（19044+ 已验证；无需 Windows Server 组件） |
| 处理器/内存 | x64 处理器；建议 ≥ 4 GB 可用内存 |
| 磁盘 | 解压后约 600 MB；`data` 随业务数据增长 |
| 网络 | 非必需。离线可启动并查询本地历史；联网用于 PBOC 汇率、SHFE ADC12 同类公开基准采集与可选云 LLM |
| **不需要** | Java / JAVA_HOME、Node.js / npm、Maven、Docker、MySQL、Redis、SQLite/H2、任何数据库服务、任何 IDE |

应用自带完整运行时（Eclipse Temurin JRE 17 + Electron 33 + Spring Boot 3.5.15），不需要在系统上安装任何运行时或依赖。

## 2. 部署步骤

1. **校验 ZIP 完整性（推荐）**：
   ```powershell
   Get-FileHash .\SupplyMindAI-<版本>-win32-x64.zip -Algorithm SHA256
   ```
   与 `SupplyMindAI-<版本>-win32-x64.zip.sha256` 中的值比对，必须完全一致。
2. **解压**到任意可写目录。支持普通路径、带空格路径与中文路径，例如：
   - `C:\SupplyMindAI`
   - `D:\我的软件\Supply Mind AI`
3. **启动**：进入解压目录，双击 `SupplyMindAI\SupplyMindAI.exe`。
4. 应用自动：
   - 检查 `runtime\jre`、后端 JAR、Vue 资源与 `data`/`logs` 写权限（不可写时**启动前明确失败并提示**，绝不静默回退到隐藏目录）；
   - 使用内置 JRE 启动 Spring Boot 后端（仅绑定 `127.0.0.1` 动态端口）；
   - 健康检查通过后显示主窗口。

## 3. 程序目录结构（解压后）

```
SupplyMindAI/
  SupplyMindAI.exe              # 双击入口（Electron 壳，单实例）
  runtime/jre/                  # 内置 Temurin JRE 17（唯一 Java 运行时）
  app/supplymind-backend.jar    # Spring Boot 后端
  app/web/                      # Vue 3 生产资源
  data/                         # 业务真值目录（JSON/CSV，唯一持久化）
    config/                     # 活动配置 + 不可变配置历史
    raw/  staging/  quarantine/ # 原始数据/生命周期/隔离
    processed/daily/            # 每日加工 CSV（按业务月轮转）
    processed/aggregate/        # 月/季/半年/年聚合 CSV（按年轮转）
    warning/  report/           # 预警与 Agent 报告 JSON（按月轮转）
    runtime/                    # 任务状态、时间状态、dirty marker、冲突证据
  logs/                         # 运行日志与 backend-url.txt
  licenses/                     # 第三方许可说明
  README.txt                    # 快速说明
```

所有业务数据均可直接用文本编辑器打开查看（UTF-8）。

## 4. 运行行为

- **单实例**：重复双击 EXE 只会聚焦已有窗口，不会启动第二个后端。
- **动态端口**：后端使用 127.0.0.1 动态端口（从不固定 8080），实际端口记录在 `logs\backend-url.txt`。
- **退出**：关闭主窗口后 Electron 与 Java 子进程全部退出；强制结束 Electron 后，Java 由父进程看门狗在约定时间内退出。端口、writer lock 均正确释放。
- **重启恢复**：重启后全部业务数据、动态配置与历史文件保持不变；异常中断（含强制杀进程）后由 dirty marker / 事务恢复机制自动恢复，最后有效文件不被覆盖。
- **启动采集**：桌面 EXE 健康启动后，共用单线程采集队列依次执行 PBOC 公开页面和 SHFE 公开日行情采集，避免多个来源并发写同一文件树。PBOC 显示最近已公布业务日中间价；SHFE 显示最近完整交易日的铸造铝合金期货主力合约结算价。二者都不是流式实时交易报价。

### 4.1 材料来源口径

- 默认两条 ADC12 来源意图使用同一个 SHFE `ad_f` 公开响应，但各自保持独立 itemId/runId/rawRef/timeline；页面实际来源固定显示“上海期货交易所铸造铝合金期货主力合约结算价（公开基准）”。
- 该数值是期货结算价基准，不能表述为 SMM/Asian Metal 或 ADC12 现货价格。
- SHFE 失败、字段漂移或无可用交易日时 fail-closed，不回退 Synthetic；用户仍可通过 Manual/LocalImport 提交真实来源数据。
- AZ91D 目前无经批准的自动免费基准，保留 Manual/LocalImport。
## 5. 数据备份 / 迁移 / 卸载

- **备份**：关闭应用后复制整个 `SupplyMindAI` 目录，或单独复制 `data` 目录（恢复时整目录覆盖回去即可）。
- **迁移**：关闭应用后把整个 `SupplyMindAI` 目录移动/复制到新位置，从新位置双击 EXE 即可继续使用；历史数据与动态配置保持。
- **卸载**：关闭应用后直接删除整个 `SupplyMindAI` 目录即可（无注册表写入、无系统服务、无残留）。

## 6. 可选：云 LLM 配置

Agent 默认使用确定性 Java 模板报告，**云模型完全可选**。如需启用（OpenAI 兼容接口），以环境变量方式在启动 EXE 前设置（不写入任何配置文件，密钥不落盘）：

```powershell
$env:SUPPLYMIND_LLM_ENABLED='true'
$env:SUPPLYMIND_LLM_PROVIDER='openai-compatible'
$env:SUPPLYMIND_LLM_MODEL='qwen-plus'
$env:SUPPLYMIND_LLM_BASE_URL='https://dashscope.aliyuncs.com/compatible-mode/v1'
$env:SUPPLYMIND_LLM_COMPLETIONS_PATH='/chat/completions'
$env:SUPPLYMIND_LLM_API_KEY='<你的密钥>'
$env:SUPPLYMIND_LLM_TIMEOUT='30s'
.\SupplyMindAI.exe
```

配置校验 fail-closed：base-url 必须是凭证无关的 HTTPS 地址，超时限定 1s-120s。云模型不可用（断网/401/429/超时/5xx/畸形响应）时自动降级为 Java 模板报告，不影响采集、校验、聚合、查询与预警。

## 7. 常见问题

| 现象 | 说明 |
|---|---|
| 双击无窗口 | 查看 `logs\` 下日志；确认目录可写、ZIP 完整性校验通过 |
| 提示目录不可写 | 应用 fail-closed 拒绝启动；请解压到可写位置（不要放到系统保护目录） |
| 重复双击只出现一个窗口 | 单实例设计，符合预期 |
| 面板无新数据 | 先查看总览顶部采集状态；联网时启动会自动采集，也可点击“重新获取”。若官方源尚未公布新业务日或网络失败，页面保留最近已发布历史并明确提示，不造数。ADC12 默认尝试 SHFE 同类公开基准；AZ91D 或无合适公开源的目标请使用 Manual 流程。 |
| 数据文件被手工损坏 | 系统显式报告缺失/损坏并隔离，不猜测修补业务数值；恢复时整目录还原备份即可 |

## 8. 验收对应

- 最终便携 ZIP、内置 JRE、loopback 启动、JSON/CSV 与无数据库依赖验收：见 `docs/evidence/Day10/`。
- 跨期轮转使用生产同一 Clock 注入边界执行，不修改宿主机系统时间；业务预期仍覆盖月/季/半年/年、回拨、高水位与跨年查询（DEC-062）。
- LLM/文件故障使用本地 stub 与临时 dataRoot 注入，不断开宿主机网络、不修改代理/防火墙/服务。
