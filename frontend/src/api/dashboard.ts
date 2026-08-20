import { getJson } from './client'
import { http } from './client'
import type {
  CurrentAcquisitionStatus,
  HistoryResponse,
  ImportResponse,
  ManualPendingResponse,
  ManualProcessResponse,
  MetricsResponse,
  OverviewResponse,
  QualityResponse,
  SourcesResponse,
  SyntheticDemoResponse
} from '../types/dashboard'

/** D7 read-only dashboard API - every business value stays a backend string. */
export function fetchOverview(): Promise<OverviewResponse | null> {
  return getJson<OverviewResponse>('/dashboard/overview')
}

export function fetchHistory(
  itemId: string,
  from: string,
  to: string
): Promise<HistoryResponse | null> {
  const params = new URLSearchParams({ itemId, from, to })
  return getJson<HistoryResponse>(`/dashboard/history?${params.toString()}`)
}

export function fetchMetrics(
  itemId: string,
  grain: string,
  fromYear: number,
  toYear: number
): Promise<MetricsResponse | null> {
  const params = new URLSearchParams({
    itemId,
    grain,
    fromYear: String(fromYear),
    toYear: String(toYear)
  })
  return getJson<MetricsResponse>(`/dashboard/metrics?${params.toString()}`)
}

export function fetchQuality(
  itemId: string,
  from: string,
  to: string
): Promise<QualityResponse | null> {
  const params = new URLSearchParams({ itemId, from, to })
  return getJson<QualityResponse>(`/dashboard/quality?${params.toString()}`)
}

export function fetchSources(): Promise<SourcesResponse | null> {
  return getJson<SourcesResponse>('/dashboard/sources')
}

/** D7 M1: manual submission -> backend accept-into-PENDING (nothing persisted). */
export async function submitManual(fields: {
  itemId: string
  source: string
  businessDate: string
  value: string
  unit: string
}): Promise<ManualPendingResponse | null> {
  const params = new URLSearchParams(fields)
  try {
    const response = await http.post<ManualPendingResponse>('/dashboard/manual', params)
    return response.data
  } catch {
    return null
  }
}


export async function processManual(runId: string): Promise<ManualProcessResponse | null> {
  try {
    const response = await http.post<ManualProcessResponse>(`/manual/${encodeURIComponent(runId)}/process`)
    return response.data
  } catch {
    return null
  }
}

/** File import -> backend intake boundary (CSV/XLSX really parsed and persisted). */
export async function submitImport(file: File): Promise<ImportResponse | null> {
  const form = new FormData()
  form.append('file', file)
  try {
    const response = await http.post<ImportResponse>('/dashboard/import', form)
    return response.data
  } catch {
    return null
  }
}

/** D7 M1: synthetic demo entry -> real deterministic demo generation (never persisted). */
export async function submitSyntheticDemo(): Promise<SyntheticDemoResponse | null> {
  try {
    const response = await http.post<SyntheticDemoResponse>('/dashboard/synthetic-demo')
    return response.data
  } catch {
    return null
  }
}

export function fetchCurrentAcquisition(): Promise<CurrentAcquisitionStatus | null> {
  return getJson<CurrentAcquisitionStatus>('/acquisition/current')
}

export async function refreshCurrentAcquisition(): Promise<CurrentAcquisitionStatus | null> {
  try {
    const response = await http.post<CurrentAcquisitionStatus>('/acquisition/current/refresh')
    return response.data
  } catch {
    return null
  }
}
