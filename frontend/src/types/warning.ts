/** D8-T02 warning wire types. All values come from the backend as strings. */

export interface WarningView {
  warningId: string
  ruleId: string
  ruleVersion: string
  itemId: string
  grain: string
  periodStart: string
  periodEnd: string
  threshold: string
  currentValue: string
  baselineValue: string
  riskLevel: string | null
  evidenceRefs: string[]
  dataStatus: string
  evaluatedAt: string | null
  demoRule: boolean
  ruleDescription: string
  acknowledged: boolean
  ackRef: string | null
}

export interface AckView {
  warningId: string
  warningRef: string
  warningFileSha256: string
  status: string
  acknowledgedAt: string | null
  dispositionNote: string
}

export interface WarningListResponse {
  itemId: string
  from: string
  to: string
  warnings: WarningView[]
}

export interface EvaluateRequest {
  ruleId: string
  ruleKind: string
  itemId: string
  grain: string
  threshold: string
  direction: string
  periodStart: string
  periodEnd: string
}

export interface EvaluateResponse {
  status: string
  message?: string
  warning?: WarningView
}
