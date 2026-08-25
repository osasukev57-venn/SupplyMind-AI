/** Warning wire types. Numeric values and percentages are backend strings; the UI never recalculates them. */

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

export interface RuleView {
  itemId: string
  displayName: string
  actualSourceName: string | null
  unit: string
  rateKind: string
  monitorEnabled: boolean
  ruleId: string
  ruleVersion: string
  lookbackDays: number
  threshold: string
  thresholdPercent: string
  direction: 'UP' | 'DOWN' | 'BOTH'
  enabled: boolean
  origin: 'SYSTEM_DEFAULT' | 'USER_CUSTOM'
  description: string
}

export interface RuleEvaluationView {
  rule: RuleView
  status: 'TRIGGERED' | 'NOT_TRIGGERED' | 'INSUFFICIENT_DATA' | 'INVALID_DATA' | 'DISABLED'
  message: string
  currentBusinessDate: string | null
  currentValue: string | null
  baselineBusinessDate: string | null
  baselineValue: string | null
  changeRatio: string | null
  changePercent: string | null
  unit: string | null
  warning: WarningView | null
}

export interface RuleListResponse {
  rules: RuleView[]
}

export interface UpdateRuleRequest {
  lookbackDays: number
  thresholdPercent: string
  direction: 'UP' | 'DOWN' | 'BOTH'
  enabled: boolean
  description: string
}

export interface RuleUpdateResponse {
  rule: RuleView
  evaluation: RuleEvaluationView
}

/** Legacy demo endpoint types retained for compatibility; the product UI no longer exposes it. */
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
