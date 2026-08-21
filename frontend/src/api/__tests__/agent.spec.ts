import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../client', () => ({
  http: {
    post: vi.fn()
  }
}))

import { http } from '../client'
import { AGENT_REQUEST_TIMEOUT_MS, queryAgent } from '../agent'
import type { AgentQueryRequest } from '../../types/agent'

const request: AgentQueryRequest = { question: '分析 ADC12', mode: 'FORMAL' }

describe('Agent API timeout contract', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uses a request-local 90 second timeout without changing ordinary API defaults', async () => {
    const response = { requestId: 'req-1' }
    vi.mocked(http.post).mockResolvedValue({ data: response })

    await expect(queryAgent(request)).resolves.toBe(response)
    expect(AGENT_REQUEST_TIMEOUT_MS).toBe(90_000)
    expect(http.post).toHaveBeenCalledWith('/agent/query', request, {
      timeout: 90_000
    })
  })

  it('maps an axios timeout to the specific non-retry warning', async () => {
    vi.mocked(http.post).mockRejectedValue({
      isAxiosError: true,
      code: 'ECONNABORTED',
      message: 'timeout of 90000ms exceeded'
    })

    await expect(queryAgent(request))
      .rejects.toThrow('智能分析处理超过 90 秒，后端可能仍在生成报告，请勿重复提交')
  })
})
