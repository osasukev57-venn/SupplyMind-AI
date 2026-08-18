/**
 * D8-T01 config/backfill wire types. Every business value comes from the backend as a string;
 * the frontend renders and controls the workflow but never computes configVersion, routes,
 * stale or aggregate values.
 */

export interface ConfigView {
  schemaVersion: string
  configVersion: number
  mode: string | null
  updatedAt: string | null
  items: ItemView[]
}

export interface ItemView {
  itemId: string
  displayName: string
  enabled: boolean
  sourceIntent: string
  providerType: string | null
  accessMethod: string | null
  actualSourceName: string
  routeDecision: string | null
  fallbackReason: string | null
  routeEffectiveAt: string | null
  supersedesItemId: string | null
  externalCode: string
  sourceFieldKey: string
  rateKind: string
  calculationVersion: string
  calculationScale: number
  displayScale: number
  roundingMode: string | null
  calendarVersion: string
  currency: string
  baseCurrency: string
  unit: string
}

export interface HistoryEntry {
  configVersion: number
  verified: boolean
  message: string | null
}

export interface CapabilityView {
  providerId: string
  providerType: string
  accessMethod: string
  actualSourceName: string
  supportsCurrentData: boolean
  supportsHistoryData: boolean
  supportedItemIds: string[]
  configuredRateKinds: string[]
}

export interface MaterialValidationRequest {
  valueMinExclusive: string
  valueMaxInclusive: string | null
  staleThresholdDays: number
  canonicalSpecCode: string
  acceptedSpecAliases: string[]
}

/**
 * Controlled ADD request: the client names the target and its business fields only. The backend
 * generates configVersion, routeEffectiveAt and audit time and validates provider capability.
 */
export interface AddItemRequest {
  itemId: string
  displayName: string
  sourceIntent: string
  providerType: string
  accessMethod: string
  actualSourceName: string
  routeDecision: string
  fallbackReason: string | null
  externalCode: string
  sourceFieldKey: string
  rateKind: string
  calculationVersion: string
  calculationScale: number
  displayScale: number
  roundingMode: string
  calendarVersion: string
  currency: string
  baseCurrency: string
  unit: string
  materialValidation: MaterialValidationRequest | null
  backfillFrom: string | null
  backfillTo: string | null
}

export interface ReplaceItemRequest {
  oldItemId: string
  newItem: AddItemRequest
}

export interface BackfillJobView {
  jobId: string
  itemId: string
  fromDate: string
  toDate: string
  status: string
  completedPeriods: string[]
  currentCheckpoint: string | null
  failureReasons: string[]
  configVersion: number
  createdAt: string | null
  updatedAt: string | null
}

export interface CurrentIntakeView {
  itemId: string
  status: string
  rawCount: number
  failureReasons: string[]
}

export interface WorkflowResult {
  config: ConfigView
  currentIntake: CurrentIntakeView | null
  backfillJobs: BackfillJobView[]
}

export interface ApiError {
  status: string
  message: string
}
