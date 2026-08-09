# D1-T02 Windows 原生 PowerShell 重放证据

- replayRunId: d1-t02-windows-replay-20260808T204852+0800
- scope: D1-T02 调查证据；不是业务 raw，不执行 Java 客户端、校验、发布或产品代码。
- JavaClient: NOT_RUN（D1-T04）
- client: Windows PowerShell Invoke-WebRequest
- Windows: Microsoft Windows 10 Pro / version=10.0.19045 / build=19045 / architecture=64-bit
- PowerShell: 5.1.19041.2673 / edition=Desktop
- timeZone: China Standard Time
- proxyMode: 显式代理（已脱敏）
- proxyDetail: 环境变量名=ALL_PROXY,HTTP_PROXY,HTTPS_PROXY,NO_PROXY
- request policy: 每客户端每 URL 仅一次初始请求；retryCount=0；未使用 -SkipCertificateCheck、-k、登录、Cookie、令牌、验证码、访问控制或反爬绕过。
- listUrl: https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html
- detailUrl: https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026080709013821880/index.html

## 请求结果

### Windows PowerShell Invoke-WebRequest / list

- attemptAt: 2026-08-08T20:53:00+08:00
- sanitized exact command: $(@{Client=Windows PowerShell Invoke-WebRequest; Label=list; Url=https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html; AttemptAt=2026-08-08T20:53:00+08:00; Command=Invoke-WebRequest -UseBasicParsing -Method Get -MaximumRedirection 5 -TimeoutSec 20 -Uri 'https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html' -OutFile 'docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-powershell-list.entity.part' -PassThru; ExitCode=1; RetryCount=0; StatusCode=; ContentType=; FinalUrl=; HeadersArtifact=; ErrorArtifact=D:\Dev\Projects\SupplyMind AI\docs\evidence\D1-T02\d1-t02-windows-replay-20260808T204852+0800-powershell-list.error.txt; EntityReceived=False; EntityPath=; EntityHash=; EntityHashPath=; ByteLength=0; FoundExpectedDetail=False; HasUsdAnchor=False; HasEurAnchor=False; HasPublishedAt=False; HasTitleDate=False; HasBodyDate=False; HasSignatureDate=False}.Command)
- exitCode: 1
- retryCount: 0
- redirect/finalUrl: NOT_OBTAINED
- httpStatus: NOT_OBTAINED
- contentType: NOT_OBTAINED
- responseHeaders: 未获得 HTTP 响应头
- clientErrorOrStructureSummary: docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-powershell-list.error.txt
- responseEntity: 未获得完整 HTTP 响应实体（无 payload SHA-256）
- listExpectedDetailLinkFound: False
- detailUSDAnchor: False
- detailEURAnchor: False
- detailPublishedAtAnchor: False
- detailTitleDateAnchor: False
- detailBodyDateAnchor: False
- detailSignatureDateAnchor: False
### Windows PowerShell Invoke-WebRequest / detail

- attemptAt: 2026-08-08T20:53:01+08:00
- sanitized exact command: $(@{Client=Windows PowerShell Invoke-WebRequest; Label=detail; Url=https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026080709013821880/index.html; AttemptAt=2026-08-08T20:53:01+08:00; Command=Invoke-WebRequest -UseBasicParsing -Method Get -MaximumRedirection 5 -TimeoutSec 20 -Uri 'https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026080709013821880/index.html' -OutFile 'docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-powershell-detail.entity.part' -PassThru; ExitCode=1; RetryCount=0; StatusCode=; ContentType=; FinalUrl=; HeadersArtifact=; ErrorArtifact=D:\Dev\Projects\SupplyMind AI\docs\evidence\D1-T02\d1-t02-windows-replay-20260808T204852+0800-powershell-detail.error.txt; EntityReceived=False; EntityPath=; EntityHash=; EntityHashPath=; ByteLength=0; FoundExpectedDetail=False; HasUsdAnchor=False; HasEurAnchor=False; HasPublishedAt=False; HasTitleDate=False; HasBodyDate=False; HasSignatureDate=False}.Command)
- exitCode: 1
- retryCount: 0
- redirect/finalUrl: NOT_OBTAINED
- httpStatus: NOT_OBTAINED
- contentType: NOT_OBTAINED
- responseHeaders: 未获得 HTTP 响应头
- clientErrorOrStructureSummary: docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-powershell-detail.error.txt
- responseEntity: 未获得完整 HTTP 响应实体（无 payload SHA-256）
- listExpectedDetailLinkFound: False
- detailUSDAnchor: False
- detailEURAnchor: False
- detailPublishedAtAnchor: False
- detailTitleDateAnchor: False
- detailBodyDateAnchor: False
- detailSignatureDateAnchor: False

## 客户端内完整路径判定素材

- list2xx: False
- listFoundExpectedDetailLink: False
- detail2xx: False
- detailEntityReceived: False
- dualCurrencyAnchors: False
- 说明：最终逐币种 connectionResult 在 PowerShell 与 curl 结果均完成后，按完成简报的“单一原生客户端完整路径”规则统一写入连接验证记录。
## 权威命令转录更正

此前自动渲染的 command 属性行未展开；以下为本次实际执行的脱敏精确命令，替代该显示行，不代表新增请求。

- replayRunId: d1-t02-windows-replay-20260808T204852+0800
- list: Invoke-WebRequest -UseBasicParsing -Method Get -MaximumRedirection 5 -TimeoutSec 20 -Uri 'https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html' -OutFile 'docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-powershell-list.entity.part' -PassThru
- detail: Invoke-WebRequest -UseBasicParsing -Method Get -MaximumRedirection 5 -TimeoutSec 20 -Uri 'https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026080709013821880/index.html' -OutFile 'docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-powershell-detail.entity.part' -PassThru
- 两次请求均 exitCode=1、retryCount=0、HTTP响应实体=未获得。
