/** D8-T03 Agent workbench wire types. All facts/values come from the backend. */

export interface ToolExecutionView {
  invocationIndex: number
  toolName: string
  toolVersion: string
  readOnly: boolean
  input: string
  output: string
  status: string
  evidenceRefs: string[]
}

export interface FactView {
  factId: string
  statement: string
  value: string
  businessDate: string | null
  period: string | null
  validationStatus: string | null
  unit?: string | null
  currency?: string | null
  configVersions?: string[]
  rowEvidenceId?: string | null
  periodStart?: string | null
  periodEnd?: string | null
  actualSourceName?: string | null
  canonicalSpecCode?: string | null
  qualityStatus?: string | null
}

export interface ScopeView {
  itemIds: string[]
  businessDate: string | null
  periodStart: string | null
  periodEnd: string | null
  timezone: string | null
}

export interface ClaimView {
  claimId: string
  text: string
  evidenceRefs: string[]
}

/** M3: controlled evidence navigation descriptor (route params from the backend, never parsed). */
export interface EvidenceLinkView {
  evidenceId: string
  evidenceType: string
  itemId: string
  businessDate: string | null
  periodStart: string | null
  periodEnd: string | null
  grain: string | null
  targetView: 'HISTORY' | 'WARNING' | 'QUALITY'
  route: string
  query: string
}

export interface CalculationBasisView {
  validationVersion: string | null
  calculationVersion: string | null
  calendarVersion: string | null
  configVersions: string[]
}

export interface RiskView {
  riskLevel: string | null
  currentValue: string | null
  baselineValue: string | null
  threshold: string | null
  dataStatus: string | null
  demoRule: boolean
  warningId?: string | null
  ruleId?: string | null
  ruleVersion?: string | null
  periodStart?: string | null
  periodEnd?: string | null
  acknowledgedStatus?: string | null
  acknowledgedAt?: string | null
}

export interface AgentQueryResponse {
  requestId: string
  answer: string | null
  llmStatus: string
  degraded: boolean
  degradeReason: string | null
  toolTrace: ToolExecutionView[]
  evidenceRefs: string[]
  reportRef: string | null
  facts: FactView[]
  generatedBy: string | null
  provider: string | null
  model: string | null
  scope: ScopeView | null
  limitations: string[]
  recommendations: string[]
  claims: ClaimView[]
  dataThrough: string | null
  evidenceLinks: EvidenceLinkView[]
  calculationBasis: CalculationBasisView | null
  risk: RiskView | null
  narrative?: NarrativeResult | null
  simulation?: boolean
  currentWarningStatus?: string
}

export interface AgentQueryRequest {
  question: string
  itemId: string
  startDate?: string
  endDate?: string
  grain?: string
  periodStart?: string
  periodEnd?: string
  month?: string
  businessDate?: string
  mode?: string
  theme?: string
}

export interface NarrativeSection {
  sectionId: string
  kind: string
  text: string
  factIds: string[]
  evidenceRefs: string[]
}
export interface NarrativeResult {
  schemaVersion: string
  adoption: 'FULL' | 'PARTIAL' | 'NONE'
  cloudStatus: string
  sections: NarrativeSection[]
  recommendations: NarrativeSection[]
  diagnostics: {sectionId: string; code: string}[]
  attempts: number
  checkScope: string
}

export interface SavedReportView {
  requestId: string
  reportRef: string
  createdAt: string
  generatedBy: string
  javaSummary: string
  narrative: NarrativeResult | null
  scope: ScopeView
}
