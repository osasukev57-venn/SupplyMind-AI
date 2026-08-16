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
}

export interface AgentQueryRequest {
  question: string
  itemId?: string
  startDate?: string
  endDate?: string
  grain?: string
  periodStart?: string
  periodEnd?: string
  month?: string
  businessDate?: string
  mode?: string
}
