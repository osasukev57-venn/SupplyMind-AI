# D1-T02 Windows 原生 curl 重放证据

- replayRunId: d1-t02-windows-replay-20260808T204852+0800
- scope: D1-T02 调查证据；不是业务 raw，不执行 Java 客户端、校验、发布或产品代码。
- JavaClient: NOT_RUN（D1-T04）
- client: Windows curl.exe
- Windows: Microsoft Windows 10 Pro / version=10.0.19045 / build=19045 / architecture=64-bit
- timeZone: China Standard Time
- proxyMode: 显式代理（已脱敏）
- proxyDetail: 环境变量名=ALL_PROXY,HTTP_PROXY,HTTPS_PROXY,NO_PROXY（值未记录）
- request policy: 每客户端每 URL 仅一次初始请求；retryCount=0；未使用 --insecure/-k、登录、Cookie、令牌、验证码、访问控制或反爬绕过。
- listUrl: https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html
- detailUrl: https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026080709013821880/index.html

## curl.exe --version

`	ext
curl 8.0.1 (Windows) libcurl/8.0.1 Schannel WinIDN
Release-Date: 2023-03-20
Protocols: dict file ftp ftps http https imap imaps pop3 pop3s smtp smtps telnet tftp
Features: AsynchDNS HSTS HTTPS-proxy IDN IPv6 Kerberos Largefile NTLM SPNEGO SSL SSPI threadsafe Unicode UnixSockets
`

## 请求结果

### Windows curl.exe / list

- attemptAt: 2026-08-08T20:54:24+08:00
- sanitized exact command: $(@{Client=Windows curl.exe; Label=list; Url=https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html; AttemptAt=2026-08-08T20:54:24+08:00; Command=curl.exe --location --max-redirs 5 --connect-timeout 20 --max-time 20 --silent --show-error --dump-header 'docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-list.headers.txt' --output 'docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-list.entity.part' --write-out 'http_code=%{http_code}\\ncontent_type=%{content_type}\\nurl_effective=%{url_effective}\\nnum_redirects=%{num_redirects}\\nsize_download=%{size_download}\\n' 'https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html'; ExitCode=35; RetryCount=0; RedirectCount=0; StatusCode=; ContentType=; FinalUrl=https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html; HeadersArtifact=D:\Dev\Projects\SupplyMind AI\docs\evidence\D1-T02\d1-t02-windows-replay-20260808T204852+0800-curl-list.headers.txt; OutputArtifact=D:\Dev\Projects\SupplyMind AI\docs\evidence\D1-T02\d1-t02-windows-replay-20260808T204852+0800-curl-list.client-output.txt; EntityReceived=False; EntityPath=; EntityHash=; EntityHashPath=; ByteLength=0; FoundExpectedDetail=False; HasUsdAnchor=False; HasEurAnchor=False; HasPublishedAt=False; HasTitleDate=False; HasBodyDate=False; HasSignatureDate=False}.Command)
- exitCode: 35
- retryCount: 0
- redirectCount: 0
- redirect/finalUrl: https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html
- httpStatus: NOT_OBTAINED
- contentType: NOT_OBTAINED
- responseHeaders: docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-list.headers.txt
- clientErrorOrStructureSummary: docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-list.client-output.txt
- responseEntity: 未获得完整 HTTP 响应实体（无 payload SHA-256）
- listExpectedDetailLinkFound: False
- detailUSDAnchor: False
- detailEURAnchor: False
- detailPublishedAtAnchor: False
- detailTitleDateAnchor: False
- detailBodyDateAnchor: False
- detailSignatureDateAnchor: False
### Windows curl.exe / detail

- attemptAt: 2026-08-08T20:54:24+08:00
- sanitized exact command: $(@{Client=Windows curl.exe; Label=detail; Url=https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026080709013821880/index.html; AttemptAt=2026-08-08T20:54:24+08:00; Command=curl.exe --location --max-redirs 5 --connect-timeout 20 --max-time 20 --silent --show-error --dump-header 'docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-detail.headers.txt' --output 'docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-detail.entity.part' --write-out 'http_code=%{http_code}\\ncontent_type=%{content_type}\\nurl_effective=%{url_effective}\\nnum_redirects=%{num_redirects}\\nsize_download=%{size_download}\\n' 'https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026080709013821880/index.html'; ExitCode=35; RetryCount=0; RedirectCount=0; StatusCode=; ContentType=; FinalUrl=https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026080709013821880/index.html; HeadersArtifact=D:\Dev\Projects\SupplyMind AI\docs\evidence\D1-T02\d1-t02-windows-replay-20260808T204852+0800-curl-detail.headers.txt; OutputArtifact=D:\Dev\Projects\SupplyMind AI\docs\evidence\D1-T02\d1-t02-windows-replay-20260808T204852+0800-curl-detail.client-output.txt; EntityReceived=False; EntityPath=; EntityHash=; EntityHashPath=; ByteLength=0; FoundExpectedDetail=False; HasUsdAnchor=False; HasEurAnchor=False; HasPublishedAt=False; HasTitleDate=False; HasBodyDate=False; HasSignatureDate=False}.Command)
- exitCode: 35
- retryCount: 0
- redirectCount: 0
- redirect/finalUrl: https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026080709013821880/index.html
- httpStatus: NOT_OBTAINED
- contentType: NOT_OBTAINED
- responseHeaders: docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-detail.headers.txt
- clientErrorOrStructureSummary: docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-detail.client-output.txt
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
- list: curl.exe --location --max-redirs 5 --connect-timeout 20 --max-time 20 --silent --show-error --dump-header 'docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-list.headers.txt' --output 'docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-list.entity.part' --write-out 'http_code=%{http_code}\\ncontent_type=%{content_type}\\nurl_effective=%{url_effective}\\nnum_redirects=%{num_redirects}\\nsize_download=%{size_download}\\n' 'https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html'
- detail: curl.exe --location --max-redirs 5 --connect-timeout 20 --max-time 20 --silent --show-error --dump-header 'docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-detail.headers.txt' --output 'docs/evidence/D1-T02/d1-t02-windows-replay-20260808T204852+0800-curl-detail.entity.part' --write-out 'http_code=%{http_code}\\ncontent_type=%{content_type}\\nurl_effective=%{url_effective}\\nnum_redirects=%{num_redirects}\\nsize_download=%{size_download}\\n' 'https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026080709013821880/index.html'
- 两次请求均 exitCode=35、retryCount=0、http_code=000、HTTP响应实体=未获得。
