# Cloud LLM Provider Integration — 2026-08-18

## Scope

- Local model deployment: `NONE`
- Provider contract: OpenAI-compatible cloud Chat Completions
- Spring AI: `1.1.8`
- SupplyMind application port: existing `LLMService`
- Infrastructure adapter: existing `SpringAiLlmService`
- Concrete `ChatModel`: new conditional `CloudLlmConfiguration`

## Security and startup

- Cloud client is created only when `SUPPLYMIND_LLM_ENABLED=true`.
- API key/model/base URL are required only when cloud is explicitly enabled.
- Disabled/no-credential startup remains valid and uses `JAVA_TEMPLATE` fallback.
- Base URL must be credential-free HTTPS; query, fragment and URL user-info are rejected.
- Completions path rejects traversal/query/fragment forms.
- Timeout is bounded to 1–120 seconds.
- API key is not stored in source, YAML, tests, reports or evidence.
- `scripts/configure-cloud-llm.ps1` accepts the key only through hidden secure input.

## Verification

- Targeted: `CloudLlmConfigurationTest,FoundationStartupAcceptanceTest`
  - tests: 7
  - failures/errors/skipped: 0/0/0
- Full backend regression:
  - tests: 639
  - failures: 0
  - errors: 0
  - skipped: 9
  - the only added skip is `CloudLlmRealApiAcceptanceTest`, gated by
    `-Dsupplymind.llm.real=true`; the previous eight skips are unchanged.

## Current gate

- Provider plumbing and environment-variable setup: `PASS`
- No-credential startup/fallback: `PASS`
- Real cloud network/API call: `PASS`
- Provider: Alibaba Bailian, Beijing region
- Base URL: `https://dashscope.aliyuncs.com/compatible-mode/v1`
- Completions path: `/chat/completions`
- Model: `qwen-plus`
- Runner: `CloudLlmRealApiAcceptanceTest`
- Result: 1 test / 0 failures / 0 errors / 0 skipped
- Raw runner: `TEST-com.supplymind.agent.infrastructure.springai.CloudLlmRealApiAcceptanceTest.xml`
- Raw runner SHA-256: `0081DCDE18454837CF04C41F5212D3D7FF7F9F0D4EFE5514E6DF6E0684C4C88C`
- Full Spring ApplicationContext with enabled cloud configuration: 2/2 PASS, no extra model call

The first request reached Bailian but returned `401 invalid_api_key` because the user environment
value had been copied with documentation placeholder brackets (`<...>`). The brackets were
removed without printing the secret; the immediately repeated gated request passed. No failed-run
stack trace or credential value is part of the formal PASS evidence.

The production validator and setup script now reject placeholder brackets before any request.
