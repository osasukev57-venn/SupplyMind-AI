# SupplyMind Cloud LLM API Setup

SupplyMind does not require or start a local model. The P0 Agent uses a cloud
OpenAI-compatible Chat Completions API through Spring AI. Without credentials, the application
still starts and returns the deterministic `JAVA_TEMPLATE` fallback.

## Secure Windows setup

Do not place the API key in Git, YAML, `.env`, a command-line argument, chat, screenshot, or
evidence file. From the repository root run:

```powershell
.\scripts\configure-cloud-llm.ps1 `
  -BaseUrl 'https://api.openai.com' `
  -Model '<cloud-model-name>' `
  -CompletionsPath '/v1/chat/completions' `
  -Verify
```

The script prompts for the API key with hidden input, writes it to the current Windows user's
environment, and starts the one gated real-cloud test in the same process. It never prints the
key. Open a new terminal before launching SupplyMind normally.

For another OpenAI-compatible vendor, use the vendor's HTTPS API root, exact model name and
Chat Completions path. Some vendors use a base URL ending in `/v1` together with
`-CompletionsPath '/chat/completions'`; do not duplicate `/v1` in both values.

## Variables

| Variable | Meaning |
|---|---|
| `SUPPLYMIND_LLM_ENABLED` | Must be `true` to create the cloud client |
| `SUPPLYMIND_LLM_PROVIDER` | `openai-compatible` or `openai` |
| `SUPPLYMIND_LLM_BASE_URL` | Credential-free HTTPS API root |
| `SUPPLYMIND_LLM_MODEL` | Exact cloud model ID |
| `SUPPLYMIND_LLM_COMPLETIONS_PATH` | Chat Completions endpoint path |
| `SUPPLYMIND_LLM_TIMEOUT` | `1s` through `120s`; default `30s` |
| `SUPPLYMIND_LLM_API_KEY` | Secret; never log or commit |

You may inspect non-secret values after opening a new terminal:

```powershell
Get-Item Env:SUPPLYMIND_LLM_ENABLED,Env:SUPPLYMIND_LLM_PROVIDER,Env:SUPPLYMIND_LLM_BASE_URL,Env:SUPPLYMIND_LLM_MODEL,Env:SUPPLYMIND_LLM_COMPLETIONS_PATH,Env:SUPPLYMIND_LLM_TIMEOUT
```

Do not print `Env:SUPPLYMIND_LLM_API_KEY`.

To remove all persisted SupplyMind LLM variables:

```powershell
.\scripts\configure-cloud-llm.ps1 -Clear
```

Windows user environment variables are appropriate for this local desktop project but are not a
dedicated secrets vault. A production deployment should inject the same variables from its
platform secret manager.
