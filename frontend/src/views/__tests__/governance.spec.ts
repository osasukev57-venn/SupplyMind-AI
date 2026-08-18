import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

// All API modules mocked so views render their full templates deterministically.
vi.mock('../../api/dashboard', () => ({
  fetchOverview: vi.fn(),
  fetchHistory: vi.fn(),
  fetchMetrics: vi.fn(),
  fetchQuality: vi.fn(),
  fetchSources: vi.fn(),
  submitManual: vi.fn(),
  submitImport: vi.fn(),
  submitSyntheticDemo: vi.fn()
}))

vi.mock('../../api/config', () => ({
  fetchConfigItems: vi.fn(),
  fetchConfigHistory: vi.fn(),
  fetchCapabilities: vi.fn(),
  fetchBackfillJobs: vi.fn(),
  addItem: vi.fn(),
  setEnabled: vi.fn(),
  replaceItem: vi.fn(),
  createBackfillJob: vi.fn(),
  runBackfillJob: vi.fn(),
  retryBackfillJob: vi.fn()
}))

vi.mock('../../api/warning', () => ({
  fetchWarnings: vi.fn(),
  acknowledgeWarning: vi.fn(),
  evaluateWarning: vi.fn()
}))

vi.mock('../../api/agent', () => ({
  queryAgent: vi.fn()
}))

import DashboardView from '../DashboardView.vue'
import HistoryView from '../HistoryView.vue'
import QualityView from '../QualityView.vue'
import SourcesView from '../SourcesView.vue'
import ConfigView from '../ConfigView.vue'
import WarningView from '../WarningView.vue'
import AgentView from '../AgentView.vue'
import App from '../../App.vue'
import { fetchOverview, fetchHistory, fetchMetrics, fetchQuality, fetchSources } from '../../api/dashboard'
import { fetchConfigItems, fetchConfigHistory, fetchCapabilities, fetchBackfillJobs } from '../../api/config'
import { fetchWarnings } from '../../api/warning'
import { queryAgent } from '../../api/agent'
import type { ConfigView as ConfigViewType } from '../../types/config'

const configItems: ConfigViewType = {
  schemaVersion: '1.0',
  configVersion: 2,
  mode: 'FORMAL',
  updatedAt: '2026-08-17T00:00:00+08:00',
  items: [
    {
      itemId: 'FX.USD.CNY.PBOC_MID',
      displayName: '美元/人民币',
      enabled: true,
      sourceIntent: 'PBOC',
      providerType: 'official_web',
      accessMethod: 'public_official_html',
      actualSourceName: '中国人民银行官网（授权中国外汇交易中心公布）',
      routeDecision: 'PRIMARY',
      fallbackReason: null,
      routeEffectiveAt: '2026-08-17T00:00:00+08:00',
      supersedesItemId: null,
      externalCode: 'USD',
      sourceFieldKey: '1美元对人民币',
      rateKind: '人民币汇率中间价',
      calculationVersion: 'arithmetic-mean-v1',
      calculationScale: 8,
      displayScale: 4,
      roundingMode: 'HALF_UP',
      calendarVersion: 'weekday-asia-shanghai-v1',
      currency: 'CNY',
      baseCurrency: 'USD',
      unit: 'CNY/1 USD'
    },
    {
      itemId: 'MAT.ADC12.SMM',
      displayName: 'ADC12铝合金锭（SMM意图）',
      enabled: true,
      sourceIntent: 'SMM',
      providerType: 'manual',
      accessMethod: 'manual',
      actualSourceName: '人工录入（Manual）',
      routeDecision: 'FALLBACK_MANUAL',
      fallbackReason: 'MANUAL_FALLBACK',
      routeEffectiveAt: '2026-08-17T00:00:00+08:00',
      supersedesItemId: null,
      externalCode: 'ADC12',
      sourceFieldKey: 'material-field-key',
      rateKind: 'material',
      calculationVersion: 'arithmetic-mean-v1',
      calculationScale: 2,
      displayScale: 2,
      roundingMode: 'HALF_UP',
      calendarVersion: 'weekday-asia-shanghai-v1',
      currency: 'CNY',
      baseCurrency: 'CNY',
      unit: '元/吨'
    }
  ]
}

/**
 * Governance-isolation contract: user-facing rendered text must not leak internal development
 * task ids, decision ids, acceptance ids, priority tokens or implementation vocabulary.
 * These belong in comments, tests and evidence - never in page copy.
 */
const FORBIDDEN_PATTERNS = [
  /D[0-9]+-T[0-9]+/,
  /Day[0-9]+/,
  /DEC-[0-9]+/,
  /EXT-[0-9]+/,
  /AT-[A-Z0-9-]+/,
  /\bP[012]\b/,
  /sidecar/,
  /capability/,
  /前端不计算/,
  /后端编排/,
  /Java确定性规则/
]

function mountWithRouter(component: unknown) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/dashboard', component: { template: '<div />' } },
      { path: '/history', component: { template: '<div />' } },
      { path: '/quality', component: { template: '<div />' } },
      { path: '/sources', component: { template: '<div />' } },
      { path: '/config', component: { template: '<div />' } },
      { path: '/warning', component: { template: '<div />' } },
      { path: '/agent', component: { template: '<div />' } }
    ]
  })
  return mount(component as never, { attachTo: document.body, global: { plugins: [router] } })
}

function assertClean(text: string, page: string) {
  for (const pattern of FORBIDDEN_PATTERNS) {
    expect(text.match(pattern), `${page} must not render ${pattern}`).toBeNull()
  }
}

describe('governance isolation (UI product copy)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchOverview).mockResolvedValue({
      mode: 'FORMAL',
      items: [],
      warnings: []
    })
    vi.mocked(fetchConfigItems).mockResolvedValue(configItems)
    vi.mocked(fetchConfigHistory).mockResolvedValue([
      { configVersion: 1, verified: true, message: null }
    ])
    vi.mocked(fetchCapabilities).mockResolvedValue({ providers: [] })
    vi.mocked(fetchBackfillJobs).mockResolvedValue({ jobs: [] })
    vi.mocked(fetchWarnings).mockResolvedValue({
      itemId: 'MAT.ADC12.SMM',
      from: '2026-07-01',
      to: '2026-08-17',
      warnings: []
    })
    vi.mocked(fetchQuality).mockResolvedValue({
      itemId: 'MAT.ADC12.SMM',
      latestStatus: 'VERIFIED',
      rows: [],
      warnings: [],
      evidenceIssues: []
    })
    vi.mocked(fetchHistory).mockResolvedValue({
      itemId: 'MAT.ADC12.SMM',
      fromDate: '2026-01-01',
      toDate: '2026-12-31',
      points: [],
      chart: { width: 640, height: 160, points: [] },
      evidenceIssues: [],
      dataThrough: null
    })
    vi.mocked(fetchMetrics).mockResolvedValue({
      itemId: 'MAT.ADC12.SMM',
      grain: 'month',
      fromYear: 2026,
      toYear: 2026,
      rows: [],
      evidenceIssues: []
    })
    vi.mocked(fetchSources).mockResolvedValue({
      mode: 'FORMAL',
      items: configItems.items.map((item) => ({
        itemId: item.itemId,
        displayName: item.displayName,
        enabled: item.enabled,
        sourceIntent: item.sourceIntent,
        providerType: item.providerType,
        accessMethod: item.accessMethod,
        actualSourceName: item.actualSourceName,
        routeDecision: item.routeDecision,
        fallbackReason: item.fallbackReason,
        routeEffectiveAt: item.routeEffectiveAt
      })),
      manualEntry: { status: 'PENDING', message: '受理后进入待处理' },
      importEntry: { status: 'PENDING', message: '上传后由系统解析' }
    })
    vi.mocked(queryAgent).mockResolvedValue({
      requestId: 'req-1',
      answer: '分析完成',
      llmStatus: 'UNAVAILABLE',
      degraded: true,
      degradeReason: null,
      toolTrace: [],
      evidenceRefs: [],
      reportRef: 'report/2026-08/report-1.json',
      facts: [],
      generatedBy: 'JAVA_TEMPLATE',
      provider: null,
      model: null,
      scope: { itemIds: ['MAT.ADC12.SMM'], businessDate: null, periodStart: null, periodEnd: null, timezone: 'Asia/Shanghai' },
      limitations: ['fallback'],
      recommendations: [],
      claims: [],
      dataThrough: null,
      evidenceLinks: [],
      calculationBasis: null,
      risk: null
    })
  })

  it('dashboard, history, quality, sources, config, warning, agent pages contain no governance tokens', async () => {
    const pages: Array<[string, unknown]> = [
      ['dashboard', DashboardView],
      ['history', HistoryView],
      ['quality', QualityView],
      ['sources', SourcesView],
      ['config', ConfigView],
      ['warning', WarningView],
      ['agent', AgentView]
    ]
    for (const [name, component] of pages) {
      const wrapper = mountWithRouter(component)
      await flushPromises()
      await flushPromises()
      assertClean(wrapper.text(), name)
      wrapper.unmount()
    }
  })

  it('app shell and navigation contain no governance tokens', async () => {
    const wrapper = mountWithRouter(App)
    await flushPromises()
    assertClean(wrapper.text(), 'app-shell')
    expect(wrapper.text()).toContain('SupplyMind')
    wrapper.unmount()
  })

  it('internal enums are displayed through friendly labels, not raw wire values', async () => {
    const configWrapper = mountWithRouter(ConfigView)
    await flushPromises()
    await flushPromises()
    const configText = configWrapper.text()
    // Wire enums must not appear as primary user copy.
    expect(configText).not.toMatch(/official_web/)
    expect(configText).not.toMatch(/FALLBACK_MANUAL/)
    expect(configText).not.toMatch(/\bManual\b|\bLocalImport\b|\bSyntheticDemo\b/)
    configWrapper.unmount()

    const sourcesWrapper = mountWithRouter(SourcesView)
    await flushPromises()
    await flushPromises()
    const sourcesText = sourcesWrapper.text()
    expect(sourcesText).not.toMatch(/official_web/)
    expect(sourcesText).not.toMatch(/FALLBACK_MANUAL/)
    expect(sourcesText).not.toMatch(/\bManual\b|\bLocalImport\b|\bSyntheticDemo\b/)
    sourcesWrapper.unmount()
  })
})
