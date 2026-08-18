/**
 * Display-layer label mappings for the Industrial Operations Console.
 * These map BACKEND-returned enum/wire values to user-facing Chinese labels.
 * They are pure presentation (no business computation): the frontend never
 * derives values, statuses or semantics - it only renders what the backend
 * already decided, using a friendly label where the wire value is a machine
 * token. DEC-008 is preserved: no parseFloat/Number/toFixed anywhere.
 */

const PROVIDER_LABELS: Record<string, string> = {
  official_web: '官方网站',
  authorized_api: '授权接口',
  free_public: '免费公开',
  manual: '人工录入',
  local_import: '本地导入',
  synthetic_demo: '演示数据'
}

const ROUTE_LABELS: Record<string, string> = {
  primary: '主要路线',
  PRIMARY: '主要路线',
  fallback_free_public: '免费公开降级',
  FALLBACK_FREE_PUBLIC: '免费公开降级',
  fallback_manual: '人工补录',
  FALLBACK_MANUAL: '人工补录',
  direct_local_import: '直接导入',
  DIRECT_LOCAL_IMPORT: '直接导入',
  synthetic_demo: '演示路线'
}

const ACCESS_LABELS: Record<string, string> = {
  public_official_html: '公开网页',
  authorized_api: '授权接口',
  free_public_web: '公开网页',
  manual: '人工录入',
  local_import: '本地导入',
  synthetic_demo: '演示数据'
}

const STAGE_LABELS: Record<string, string> = {
  RECEIVED: '已受理',
  PARSED: '已解析',
  VALIDATED: '已校验',
  PUBLISHED: '已发布'
}

const VALIDATION_LABELS: Record<string, string> = {
  PENDING: '待处理',
  VERIFIED: '已核验',
  VERIFIED_WITH_NOTICE: '已核验（有提示）',
  REJECTED: '已拒绝',
  CONFLICT: '冲突'
}

const JOB_STATUS_LABELS: Record<string, string> = {
  WAITING: '等待中',
  AWAITING_MANUAL_INPUT: '等待人工录入',
  RUNNING: '运行中',
  PARTIAL_SUCCESS: '部分完成',
  SUCCEEDED: '已完成',
  FAILED: '失败'
}

const MODE_LABELS: Record<string, string> = {
  FORMAL: '正式运行',
  DEMO: '演示模式',
  TEST: '测试模式'
}

const GRAIN_LABELS: Record<string, string> = {
  daily: '按日',
  month: '按月',
  quarter: '按季',
  halfyear: '按半年',
  year: '按年'
}

const REASON_LABELS: Record<string, string> = {
  MANUAL_FALLBACK: '人工补录',
  SOURCE_UNAVAILABLE: '来源不可用',
  PROVIDER_TERMINATED: '来源已终止',
  RATE_INVALID: '数值无效',
  NO_RECENT_UPDATE: '长时间未更新',
  SOURCE_DRIFT: '来源数据漂移'
}

const SOURCE_ID_LABELS: Record<string, string> = {
  'pboc-official-web': '中国人民银行官网',
  'am-authorized-api': 'Asian Metal 授权接口',
  'smm-authorized-api': '上海有色网授权接口',
  Manual: '人工录入',
  LocalImport: '本地文件导入',
  SyntheticDemo: '演示数据'
}

/** Map an enum wire value to a display label; unknown values pass through untouched. */
export function providerLabel(value: string | null | undefined): string {
  if (value == null) return '—'
  return PROVIDER_LABELS[value] ?? value
}

export function routeLabel(value: string | null | undefined): string {
  if (value == null) return '—'
  return ROUTE_LABELS[value] ?? value
}

export function accessLabel(value: string | null | undefined): string {
  if (value == null) return '—'
  return ACCESS_LABELS[value] ?? value
}

export function stageLabel(value: string | null | undefined): string {
  if (value == null) return '—'
  return STAGE_LABELS[value] ?? value
}

export function validationLabel(value: string | null | undefined): string {
  if (value == null) return '—'
  return VALIDATION_LABELS[value] ?? value
}

export function jobStatusLabel(value: string | null | undefined): string {
  if (value == null) return '—'
  return JOB_STATUS_LABELS[value] ?? value
}

export function modeLabel(value: string | null | undefined): string {
  if (value == null) return '—'
  return MODE_LABELS[value] ?? value
}

export function grainLabel(value: string | null | undefined): string {
  if (value == null) return '—'
  return GRAIN_LABELS[value] ?? value
}

export function fallbackReasonLabel(value: string | null | undefined): string {
  if (value == null) return '—'
  return REASON_LABELS[value] ?? value
}

/** Presentation-only source name cleanup. The authoritative source value remains unchanged. */
export function sourceDisplayName(value: string | null | undefined): string {
  if (value == null || value.trim() === '') return '—'
  const direct = SOURCE_ID_LABELS[value]
  if (direct) return direct
  return value
    .replace(/\s*[（(](Manual|LocalImport|SyntheticDemo|am-authorized-api|smm-authorized-api|pboc-official-web)[）)]/g, '')
    .trim()
}

/** EvidenceIssue.reason is audit detail; the product surface renders a stable action-oriented message. */
export function evidenceIssueMessage(status: string | null | undefined): string {
  if (status === 'MISSING') return '请求期间尚无可用数据'
  if (status === 'CORRUPT' || status === 'INVALID') return '数据证据未通过完整性校验'
  return '数据证据当前不可用'
}
