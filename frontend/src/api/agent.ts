import axios from 'axios'
import { http } from './client'
import type { AgentQueryRequest, AgentQueryResponse } from '../types/agent'

/** Agent performs two cloud phases plus read-only tools; ordinary APIs keep the global 15s timeout. */
export const AGENT_REQUEST_TIMEOUT_MS = 90_000

/**
 * D8-T03 Agent API client. POST /api/agent/query returns the full structured response
 * (D6-T04 fields + D8-T03 report projection). The frontend renders backend facts only and
 * never computes risk, recommendations or results.
 */
export async function queryAgent(request: AgentQueryRequest): Promise<AgentQueryResponse | null> {
  try {
    const response = await http.post<AgentQueryResponse>('/agent/query', request, {
      timeout: AGENT_REQUEST_TIMEOUT_MS
    })
    return response.data
  } catch (error) {
    if (axios.isAxiosError(error)) {
      const timedOut = error.code === 'ECONNABORTED'
        || error.code === 'ETIMEDOUT'
        || error.message.toLowerCase().includes('timeout')
      if (timedOut) {
        throw new Error('智能分析处理超过 90 秒，后端可能仍在生成报告，请勿重复提交')
      }
      const apiMessage = typeof error.response?.data === 'object'
        && error.response?.data !== null
        && 'message' in error.response.data
        && typeof error.response.data.message === 'string'
        ? error.response.data.message
        : null
      if (apiMessage) {
        throw new Error(`智能分析失败：${apiMessage}`)
      }
    }
    throw new Error('无法连接本地分析服务，请确认应用仍在运行')
  }
}
