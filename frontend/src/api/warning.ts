import { getJson } from './client'
import { http } from './client'
import type {
  AckView,
  EvaluateRequest,
  EvaluateResponse,
  WarningListResponse,
  WarningView
} from '../types/warning'

/**
 * D8-T02 warning API. Evaluation is a deterministic Java chain (demoRule=true only); the
 * acknowledgement writes a DEC-061 sidecar - the original warning evidence is never modified.
 * The frontend never computes thresholds, risk levels or completeness.
 */

export function fetchWarnings(
  itemId: string,
  from: string,
  to: string
): Promise<WarningListResponse | null> {
  const params = new URLSearchParams({ itemId, from, to })
  return getJson<WarningListResponse>(`/warnings?${params.toString()}`)
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

export async function evaluateWarning(request: EvaluateRequest): Promise<EvaluateResponse | null> {
  try {
    const response = await http.post<EvaluateResponse>('/warnings/evaluate', request)
    return response.data
  } catch {
    return null
  }
}
