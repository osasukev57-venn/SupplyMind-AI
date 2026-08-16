/**
 * D7 frozen dashboard DTO types - mirror the backend DashboardV1 JSON contract 1:1.
 * Values are ALWAYS strings: the backend produces exact BigDecimal strings and the browser
 * never recomputes business values (DEC-008). No number parsing, no client-side math.
 */
export interface OverviewResponse {
  mode: string | null
  items: ItemCard[]
  warnings: string[]
}

export interface ItemCard {
  itemId: string
  displayName: string
  enabled: boolean
  latestValue: string | null
  businessDate: string | null
  unit: string | null
  currency: string | null
  dataThrough: string | null
  source: SourceView
  quality: QualityView
  warningSummary: string | null
  aggregateSummary: AggregateSummary | null
}

export interface AggregateSummary {
  grain: string
  periodStart: string
  periodEnd: string
  value: string
  unit: string | null
}

export interface SourceView {
  providerType: string | null
  accessMethod: string | null
  actualSourceName: string | null
  routeDecision: string | null
  fallbackReason: string | null
}

export interface QualityView {
  status: string
  validationStatus: string | null
  validationVersion: string | null
  stale: boolean
  updatedAt: string | null
}

export interface HistoryResponse {
  itemId: string
  fromDate: string
  toDate: string
  points: HistoryPoint[]
  chart: Chart
  evidenceIssues: EvidenceIssue[]
  dataThrough: string | null
}

export interface Chart {
  width: number
  height: number
  points: ChartPoint[]
}

/** Backend-computed display coordinate; label carries the exact businessDate + value string. */
export interface ChartPoint {
  label: string
  x: string
  y: string
}

/** Business evidence reference (period + status + reason) - internal CSV paths never leave the backend. */
export interface EvidenceIssue {
  periods: string[]
  status: string
  reason: string
}

export interface HistoryPoint {
  businessDate: string
  value: string
  unit: string | null
  actualSourceName: string | null
  validationStatus: string | null
  validationVersion: string | null
}

export interface MetricsResponse {
  itemId: string
  grain: string
  fromYear: number
  toYear: number
  rows: MetricRow[]
  evidenceIssues: EvidenceIssue[]
}

export interface MetricRow {
  periodStart: string
  periodEnd: string
  value: string
  unit: string | null
  actualSourceName: string | null
  validationStatus: string | null
  validationVersion: string | null
}

export interface QualityResponse {
  itemId: string
  latestStatus: string
  rows: QualityRow[]
  warnings: WarningView[]
  evidenceIssues: EvidenceIssue[]
}

export interface QualityRow {
  businessDate: string
  value: string
  unit: string | null
  actualSourceName: string | null
  providerType: string | null
  accessMethod: string | null
  validationStatus: string | null
  validationVersion: string | null
  stale: boolean
  completeness: string
}

export interface WarningView {
  warningId: string
  ruleId: string
  ruleVersion: string
  periodStart: string
  periodEnd: string
  value: string
  threshold: string
  riskLevel: string | null
  status: string | null
  createdAt: string | null
}

export interface SourcesResponse {
  mode: string | null
  items: SourceItem[]
  manualEntry: EntryStatus
  importEntry: EntryStatus
}

export interface SourceItem {
  itemId: string
  displayName: string
  enabled: boolean
  sourceIntent: string | null
  providerType: string | null
  accessMethod: string | null
  actualSourceName: string | null
  routeDecision: string | null
  fallbackReason: string | null
  routeEffectiveAt: string | null
}

export interface EntryStatus {
  status: string
  message: string
}
