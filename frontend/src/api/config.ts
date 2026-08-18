import { getJson } from './client'
import { http } from './client'
import type {
  AddItemRequest,
  BackfillJobView,
  CapabilityView,
  ConfigView,
  HistoryEntry,
  ReplaceItemRequest,
  WorkflowResult
} from '../types/config'

/**
 * D8-T01 config + backfill API. The frontend only sends controlled request DTOs - the backend
 * generates configVersion/routeEffectiveAt/jobId/audit time and validates capability.
 */

export function fetchConfigItems(): Promise<ConfigView | null> {
  return getJson<ConfigView>('/config/items')
}

export function fetchConfigHistory(): Promise<HistoryEntry[] | null> {
  return getJson<HistoryEntry[]>('/config/history')
}

export function fetchCapabilities(): Promise<{ providers: CapabilityView[] } | null> {
  return getJson<{ providers: CapabilityView[] }>('/config/capabilities')
}

export async function addItem(request: AddItemRequest): Promise<WorkflowResult | null> {
  try {
    const response = await http.post<WorkflowResult>('/config/items', request)
    return response.data
  } catch (error) {
    return null
  }
}

export async function setEnabled(itemId: string, enabled: boolean): Promise<ConfigView | null> {
  try {
    const response = await http.post<ConfigView>(
      `/config/items/${encodeURIComponent(itemId)}/enabled?enabled=${enabled}`
    )
    return response.data
  } catch (error) {
    return null
  }
}

export async function replaceItem(request: ReplaceItemRequest): Promise<WorkflowResult | null> {
  try {
    const response = await http.post<WorkflowResult>('/config/replace', request)
    return response.data
  } catch (error) {
    return null
  }
}

export async function createBackfillJob(
  itemId: string,
  from: string,
  to: string
): Promise<BackfillJobView | null> {
  try {
    const params = new URLSearchParams({ itemId, from, to })
    const response = await http.post<BackfillJobView>(`/backfill/jobs?${params.toString()}`)
    return response.data
  } catch (error) {
    return null
  }
}

export function fetchBackfillJobs(): Promise<{ jobs: BackfillJobView[] } | null> {
  return getJson<{ jobs: BackfillJobView[] }>('/backfill/jobs')
}

export async function runBackfillJob(jobId: string): Promise<BackfillJobView | null> {
  try {
    const response = await http.post<BackfillJobView>(
      `/backfill/jobs/${encodeURIComponent(jobId)}/run`
    )
    return response.data
  } catch (error) {
    return null
  }
}

export async function retryBackfillJob(jobId: string): Promise<BackfillJobView | null> {
  try {
    const response = await http.post<BackfillJobView>(
      `/backfill/jobs/${encodeURIComponent(jobId)}/retry`
    )
    return response.data
  } catch (error) {
    return null
  }
}
