import { getJson, http } from './client'
import type {
  AckView,
  EvaluateRequest,
  EvaluateResponse,
  RuleEvaluationView,
  RuleListResponse,
  RuleUpdateResponse,
  UpdateRuleRequest,
  WarningListResponse
} from '../types/warning'

/**
 * Warning API. Formal rule evaluation is deterministic Java logic over published/verified data.
 * The UI receives display-ready percentages and never recalculates thresholds or change ratios.
 */

export function fetchWarnings(
  itemId: string,
  from: string,
  to: string
): Promise<WarningListResponse | null> {
  const params = new URLSearchParams({ itemId, from, to })
  return getJson<WarningListResponse>(`/warnings?${params.toString()}`)
}

export function fetchWarningRules(): Promise<RuleListResponse | null> {
  return getJson<RuleListResponse>('/warnings/rules')
}

export async function evaluateAllWarningRules(): Promise<RuleEvaluationView[] | null> {
  try {
    const response = await http.post<{ evaluations: RuleEvaluationView[] }>(
      '/warnings/rules/evaluate-all'
    )
    return response.data.evaluations
  } catch {
    return null
  }
}

export async function updateWarningRule(
  itemId: string,
  request: UpdateRuleRequest
): Promise<RuleUpdateResponse | null> {
  try {
    const response = await http.post<RuleUpdateResponse>(
      `/warnings/rules/${encodeURIComponent(itemId)}`,
      request
    )
    return response.data
  } catch {
    return null
  }
}

export async function resetWarningRule(itemId: string): Promise<RuleUpdateResponse | null> {
  try {
    const response = await http.post<RuleUpdateResponse>(
      `/warnings/rules/${encodeURIComponent(itemId)}/reset`
    )
    return response.data
  } catch {
    return null
  }
}

export async function acknowledgeWarning(
  itemId: string,
  warningId: string,
  dispositionNote: string
): Promise<AckView | null> {
  try {
    const response = await http.post<AckView>(
      `/warnings/${encodeURIComponent(warningId)}/ack?itemId=${encodeURIComponent(itemId)}`,
      { dispositionNote }
    )
    return response.data
  } catch {
    return null
  }
}

/** Legacy demo endpoint retained for old clients/tests; not used by the formal warning page. */
export async function evaluateWarning(request: EvaluateRequest): Promise<EvaluateResponse | null> {
  try {
    const response = await http.post<EvaluateResponse>('/warnings/evaluate', request)
    return response.data
  } catch {
    return null
  }
}
