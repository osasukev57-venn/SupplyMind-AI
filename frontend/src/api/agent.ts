import { http } from './client'
import type { AgentQueryRequest, AgentQueryResponse } from '../types/agent'

/**
 * D8-T03 Agent API client. POST /api/agent/query returns the full structured response
 * (D6-T04 fields + D8-T03 report projection). The frontend renders backend facts only and
 * never computes risk, recommendations or results.
 */
export async function queryAgent(request: AgentQueryRequest): Promise<AgentQueryResponse | null> {
  try {
    const response = await http.post<AgentQueryResponse>('/agent/query', request)
    return response.data
  } catch (error) {
    return null
  }
}
