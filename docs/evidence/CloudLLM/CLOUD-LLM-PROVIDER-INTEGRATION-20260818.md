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
  - tests: 638
  - failures: 0
  - errors: 0
  - skipped: 9
  - the only added skip is `CloudLlmRealApiAcceptanceTest`, gated by
    `-Dsupplymind.llm.real=true`; the previous eight skips are unchanged.

## Current gate

- Provider plumbing and environment-variable setup: `PASS`
- No-credential startup/fallback: `PASS`
- Real cloud network/API call: `NOT_RUN/PENDING_USER_CREDENTIAL`
- No real-cloud `PASS` is claimed until the hidden-key setup script completes with `-Verify`.
