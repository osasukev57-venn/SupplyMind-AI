import { getJson } from './client'
import type {
  HistoryResponse,
  MetricsResponse,
  OverviewResponse,
  QualityResponse,
  SourcesResponse
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
