# D9-T05 generate README.txt inside the portable root.
param(
    [string]$Root = (Join-Path $PSScriptRoot '..\..\portable\SupplyMindAI'),
    [string]$Version = '0.9.0'
)
$ErrorActionPreference = 'Stop'

$readme = @"
SupplyMind AI - Windows Desktop (portable)
==========================================
Version: $Version

1. 运行方式 (Windows)
----------------------
- 将整个 SupplyMindAI 目录（或解压 SupplyMindAI-*.zip）复制到一个 可写 的目录
  （例如 D:\SupplyMindAI）。路径可以包含空格和中文。
- 双击 SupplyMindAI.exe 启动。首次启动会自动创建 data/ 与 logs/。
- 无需安装 Java、Node.js、Maven、Docker 或数据库：运行时使用随包内置 JRE
  (runtime/jre)。关闭应用窗口后，后端进程会自动退出，不会残留 Java 进程。

2. 数据目录 (data/)
-------------------
- 所有业务数据（raw/processed/warning/report/config 等）以 JSON/CSV 保存在
  程序根目录的 data/ 下，可直接检查。
- data/ 不可写时应用会拒绝启动并给出提示；请将整个目录移到可写位置。
- 整个目录可整体移动/复制后继续运行，数据不丢失。

3. 日志位置 (logs/)
-------------------
- logs/backend.log       后端（Spring Boot）运行日志
- logs/backend-url.txt   本次启动使用的本机地址（动态端口）
- 故障排查时请提供这两个文件。

4. 环境变量（可选）
-------------------
以下变量均只通过环境传递给后端 Java 进程，不会被写入日志或界面：
- SUPPLYMIND_LLM_ENABLED  设为 true 启用云 LLM（默认不启用，使用本地模板降级）
- SUPPLYMIND_LLM_PROVIDER 默认 openai-compatible
- SUPPLYMIND_LLM_MODEL    例如 qwen-plus
- SUPPLYMIND_LLM_BASE_URL 例如 https://dashscope.aliyuncs.com/compatible-mode/v1
- SUPPLYMIND_LLM_API_KEY  LLM API 密钥（仅环境变量，禁止写入任何文件）
- SUPPLYMIND_MANUAL_OPERATOR_REF 手工录入操作者标识（默认 local-operator）

5. LLM 配置方式（可选）
-----------------------
- 未配置时，Agent 工作台使用内置 Java 模板生成可追溯报告（核心功能不受影响）。
- 配置方式（只需在本次启动前设置环境变量）：
  在资源管理器中找到 SupplyMindAI.exe，按住 Shift 在空白处右键选择
  “在此处打开 PowerShell 窗口”（或按 Win+R 输入 cmd），然后依次执行：
      set SUPPLYMIND_LLM_ENABLED=true
      set SUPPLYMIND_LLM_PROVIDER=openai-compatible
      set SUPPLYMIND_LLM_MODEL=qwen-plus
      set SUPPLYMIND_LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
      set SUPPLYMIND_LLM_COMPLETIONS_PATH=/chat/completions
      set SUPPLYMIND_LLM_TIMEOUT=30s
      set SUPPLYMIND_LLM_API_KEY=你的密钥
      SupplyMindAI.exe
  - 注意：密钥只通过环境变量传给后端 Java 进程，不会被写入日志或界面。
    不要把它们写进任何配置文件。
- 云模型不可用（断网/超时/缺密钥）时自动降级到 Java 模板，不会返回无依据回答。

6. 故障排查
-----------
- 启动即退出：检查 data/ 与 logs/ 是否可写；检查是否有其他实例正在运行。
- 端口冲突：应用每次启动自动选择空闲本机端口，不依赖固定端口。
- 残留进程：关闭应用后如有 java.exe 残留，请结束该进程后重新启动；
  数据锁由操作系统在进程退出时自动释放，不会阻塞再次启动。
- 页面空白：查看 logs/backend.log；如后端健康检查超时，应用会提示日志路径。
- 代理/防火墙：本应用只监听 127.0.0.1，不会对外部网络开放端口。

7. 版本与校验
---------------
- 后端：Spring Boot 3.5.15 / Spring AI 1.1.8（Java 17 目标字节码）
- 内置 JRE：Eclipse Temurin 17（GPLv2 with Classpath Exception）
- 前端：Vue 3 / Vite（构建产物位于 app/web）
- 校验：ZIP 同目录提供 .sha256 校验和文件。
"@

Set-Content -LiteralPath (Join-Path $Root 'README.txt') -Value $readme -Encoding UTF8
Write-Host "[write-readme] README.txt written: $(Join-Path $Root 'README.txt')"
