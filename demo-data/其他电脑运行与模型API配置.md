# SupplyMind AI 其他电脑运行与模型配置

适用交付形态为Windows x64便携包。接收源码和直接使用软件是两种不同流程；项目不会携带开发者的模型Key，接收方使用自己的账号、模型权限与额度。

## 普通用户直接使用

1. 获取完整的Windows运行ZIP，而不是只拿源码ZIP或单独的EXE。
2. 解压到可写目录，例如D:\SupplyMindAI。保留整个SupplyMindAI文件夹中的runtime、app、resources、data和其他文件。
3. 双击SupplyMindAI.exe。包内已有Electron、Java17运行时、后端和前端，不需要另装Java、Node.js、Maven、Docker或数据库。
4. 未配置模型时，采集、录入、校验、统计、预警、历史查询仍可使用；Agent显示Java事实报告，不应当成云端分析。

当前便携包针对Windows x64，不能把同一个EXE直接当作macOS、Linux或ARM原生包。启动方式和路径已有本机测试，但不等于已在所有型号电脑实测。

## 仅本次启动配置自己的百炼模型

先关闭已有SupplyMind窗口，在SupplyMindAI.exe所在目录打开PowerShell，执行下面的命令。Key通过隐藏输入获得，不写进命令历史或配置文件；环境变量仅属于当前PowerShell及其启动的应用，不修改用户级或系统级环境。

```powershell
$secureKey = Read-Host '请输入你自己的百炼 API Key' -AsSecureString
$env:SUPPLYMIND_LLM_ENABLED = 'true'
$env:SUPPLYMIND_LLM_PROVIDER = 'openai-compatible'
$env:SUPPLYMIND_LLM_MODEL = 'qwen-plus'
$env:SUPPLYMIND_LLM_BASE_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
$env:SUPPLYMIND_LLM_COMPLETIONS_PATH = '/chat/completions'
$env:SUPPLYMIND_LLM_TIMEOUT = '90s'
$env:SUPPLYMIND_LLM_API_KEY = ([System.Net.NetworkCredential]::new('', $secureKey)).Password
try {
    Start-Process -FilePath '.\SupplyMindAI.exe' -WorkingDirectory (Get-Location).Path
} finally {
    Remove-Item Env:\SUPPLYMIND_LLM_API_KEY -ErrorAction SilentlyContinue
    Remove-Variable secureKey -ErrorAction SilentlyContinue
}
```

启动的应用已经继承Key，清理父PowerShell的变量不会清理子进程中的副本。关闭应用会结束该应用持有的凭证环境；其他非敏感变量仍只在当前PowerShell中，关闭该窗口即可清除。

上述配置针对项目已使用的百炼兼容端点。Key的账号地域、服务开通、模型权限和额度必须与实际服务匹配。仅启动程序不会自动提交Agent分析；点击提交分析才可能计费，不要为了等待结果重复点击。

模型阶段、FULL/PARTIAL/NONE和Java事实校验是不同状态。收到模型响应不等于正文全部采用，未采用不能冒充云端成功。其他厂商即使有兼容接口，也须验证JSON输出与本项目事实合同，不能承诺换一个模型名称就一定通过。

## 开发者从源码运行

需要JDK17、符合前端依赖要求的Node.js/npm，以及首次下载Maven和npm依赖所需的网络。项目带Maven Wrapper，不必使用开发者电脑上的D盘Maven路径。源码不自动读取任意.env文件，仍通过SUPPLYMIND_LLM_*环境变量配置模型。

在项目根目录打开PowerShell，将JAVA_HOME设为接收方自己的JDK17目录。若要启用云端，在同一窗口按上一节设置模型变量并隐藏输入自己的Key，然后运行后端：

```powershell
$env:JAVA_HOME = 'C:\你的JDK17目录'
Set-Location .\backend
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8080'
```

另开终端，在项目根目录运行前端：

```powershell
Set-Location .\frontend
npm ci
npm run dev
```

开发前端默认5173端口，将/api代理到127.0.0.1:8080。开发模式的8080与正式EXE的动态端口不同。不要把开发服务器暴露到公网。未配置云端也可以先运行和验证本地功能。

源码中的backend默认数据目录基于启动工作目录，通常是backend/data；正式EXE使用程序旁的data。迁移时要确认复制到实际使用的目录，不要误以为两种启动共享同一份数据。

若自行重新打Windows包，还需要安装桌面依赖并准备JDK17的jlink。当前desktop/scripts/build-jre.ps1默认JDK路径仍是开发机路径，接收方必须将JdkHome配置为自己的安装路径，不能照搬D:\Dev\SDK\Java\jdk-17。这个限制影响源码重新打包，不影响使用已经内置JRE的便携运行包。

## 把现有演示数据带到其他电脑

新发布ZIP只有初始配置，不包含本机全部验收业务数据。要复现截图，关闭应用后单独提供经允许分享的演示数据副本，并整体复制data，不只复制某一份CSV或报告。

data内的原始记录、配置历史、manifest、聚合文件、预警与确认旁挂文件、报告及证据快照相互绑定。漏掉依赖文件会导致核验失败，不能靠修改SHA或删除校验来解决。先备份接收方旧data，避免直接覆盖其业务数据。

模型Key不在data中，必须由对方重新配置。无需把开发者的环境变量、绝对路径或Key交给对方。迁移不是把本机127.0.0.1地址发给别人访问，而是在对方电脑上启动自己的本地服务。

## 当前入口的区别

- SupplyMindAI.exe：使用启动时继承的SUPPLYMIND_LLM_*设置；没有设置时默认不开云端。
- 启动-本地Agent验收.cmd：显式关闭云端，适合本地功能和历史报告重读。
- 启动-云端Agent.cmd及start-cloud-agent.ps1：面向已存在DASHSCOPE_API_KEY的百炼验收入口，不是所有厂商的通用配置界面。
- 启动-云端Agent诊断验收.cmd：额外显式开启有界本地拒绝诊断，仅用于受控验收，不把未采用正文当业务结论。
- scripts/configure-cloud-llm.ps1：现有源码辅助脚本会写入用户级环境变量；若不希望持久保存Key，不使用该脚本，采用上面的进程级启动方式。其-Verify会发起真实收费验证，不要无授权执行。

## 交付注意

不要在源码、application.yml、CSV、截图、视频、Git提交或运行包中附带个人Key。最终发送源码、运行包、SHA256、部署说明和需要分享的模拟数据；运行包与源码应对应同一候选版本。

当前PBOC汇率和SHFE公开期货基准的联网采集，与模型API是两套独立网络入口；配置模型不会使商业SMM或Asian Metal授权自动生效。对方仍需能访问相应服务，且模拟数据必须一直标明模拟。
