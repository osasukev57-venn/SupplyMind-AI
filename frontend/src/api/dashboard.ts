import { getJson } from './client'
import { http } from './client'
import type {
  HistoryResponse,
  ImportResponse,
  ManualPendingResponse,
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

/** D7 M1: file import -> backend real LocalImport boundary (CSV/XLSX really parsed). */
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
