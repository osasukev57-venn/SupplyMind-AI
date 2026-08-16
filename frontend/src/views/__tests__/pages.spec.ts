import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../../api/dashboard', () => ({
  fetchOverview: vi.fn(),
  fetchHistory: vi.fn(),
  fetchMetrics: vi.fn(),
  fetchQuality: vi.fn(),
  fetchSources: vi.fn()
}))

import DashboardView from '../DashboardView.vue'
import HistoryView from '../HistoryView.vue'
import QualityView from '../QualityView.vue'
import SourcesView from '../SourcesView.vue'
import { fetchOverview, fetchHistory, fetchQuality, fetchSources } from '../../api/dashboard'
import type { OverviewResponse, HistoryResponse, QualityResponse, SourcesResponse } from '../../types/dashboard'

const overview: OverviewResponse = {
  mode: 'FORMAL',
  items: [
    {
      itemId: 'FX.USD.CNY.PBOC_MID',
      displayName: '美元/人民币',
      enabled: true,
      latestValue: '6.7904',
      businessDate: '2026-08-10',
      unit: 'CNY/1 USD',
      currency: 'CNY',
      dataThrough: '2026-08-10',
      source: {
        providerType: 'official_web',
        accessMethod: 'public_official_html',
        actualSourceName: '中国人民银行官网',
        routeDecision: 'PRIMARY',
        fallbackReason: null
      },
      quality: {
        status: 'VERIFIED',
        validationStatus: 'VERIFIED',
        validationVersion: 'pboc-basic-validation-v1',
        stale: false,
        updatedAt: '2026-08-10T09:25:38+08:00'
      },
      warningSummary: null
    }
  ],
  warnings: []
}

const history: HistoryResponse = {
  itemId: 'FX.USD.CNY.PBOC_MID',
  fromDate: '2026-01-01',
  toDate: '2026-12-31',
  points: [
    {
      businessDate: '2026-08-10',
      value: '6.79040000',
      unit: 'CNY/1 USD',
      actualSourceName: '中国人民银行官网',
      validationStatus: 'VERIFIED',
      validationVersion: 'pboc-basic-validation-v1'
    }
  ],
  chart: {
    width: 640,
    height: 160,
    points: [{ label: '2026-08-10 6.79040000', x: '320.0', y: '76.0' }]
  },
  evidenceIssues: [],
  dataThrough: '2026-08-10'
}

const quality: QualityResponse = {
  itemId: 'FX.USD.CNY.PBOC_MID',
  latestStatus: 'VERIFIED',
  rows: [
    {
      businessDate: '2026-08-10',
      value: '6.79040000',
      unit: 'CNY/1 USD',
      actualSourceName: '中国人民银行官网',
      providerType: 'official_web',
      accessMethod: 'public_official_html',
      validationStatus: 'VERIFIED',
      validationVersion: 'pboc-basic-validation-v1',
      stale: false
    }
  ],
  warnings: [],
  evidenceIssues: []
}

const sources: SourcesResponse = {
  mode: 'FORMAL',
  items: [
    {
      itemId: 'FX.USD.CNY.PBOC_MID',
      displayName: '美元/人民币',
      enabled: true,
      sourceIntent: 'PBOC',
      providerType: 'official_web',
      accessMethod: 'public_official_html',
      actualSourceName: '中国人民银行官网',
      routeDecision: 'PRIMARY',
      fallbackReason: null,
      routeEffectiveAt: '2026-08-10T00:00:00+08:00'
    }
  ],
  manualEntry: { status: 'PENDING', message: 'Day8 contract' },
  importEntry: { status: 'PENDING', message: 'Day8 contract' }
}

describe('dashboard pages', () => {
  beforeEach(() => {
    vi.mocked(fetchOverview).mockResolvedValue(overview)
    vi.mocked(fetchHistory).mockResolvedValue(history)
    vi.mocked(fetchQuality).mockResolvedValue(quality)
    vi.mocked(fetchSources).mockResolvedValue(sources)
  })

  it('dashboard renders the exact backend value string', async () => {
    const wrapper = mount(DashboardView)
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('6.7904')
    expect(wrapper.text()).toContain('中国人民银行官网')
    expect(wrapper.text()).toContain('pboc-basic-validation-v1')
    expect(wrapper.find('.demo-banner').exists()).toBe(false)
  })

  it('dashboard shows a DEMO watermark in demo mode', async () => {
    vi.mocked(fetchOverview).mockResolvedValue({ ...overview, mode: 'DEMO' })
    const wrapper = mount(DashboardView)
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.demo-banner').exists()).toBe(true)
  })

  it('dashboard shows an error banner instead of a white screen when the API fails', async () => {
    vi.mocked(fetchOverview).mockResolvedValue(null)
    const wrapper = mount(DashboardView)
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.error-banner').exists()).toBe(true)
    expect(wrapper.text()).toContain('不可用')
  })

  it('history renders daily points and business-period missing notices', async () => {
    vi.mocked(fetchHistory).mockResolvedValue({
      ...history,
      evidenceIssues: [
        {
          periods: ['2026-01'],
          status: 'MISSING',
          reason: 'daily file(s) not found for the requested period'
        }
      ]
    })
    const wrapper = mount(HistoryView)
    await flushPromises()
    expect(wrapper.text()).toContain('6.79040000')
    expect(wrapper.text()).toContain('缺失')
    expect(wrapper.text()).toContain('2026-01')
    expect(wrapper.text()).not.toContain('processed/')
  })

  it('quality renders rows and warning list', async () => {
    vi.mocked(fetchQuality).mockResolvedValue({
      ...quality,
      warnings: [
        {
          warningId: 'w1',
          ruleId: 'r1',
          ruleVersion: 'v1',
          periodStart: '2026-08-01',
          periodEnd: '2026-08-31',
          value: '7.00000000',
          threshold: '5.00000000',
          riskLevel: 'HIGH',
          status: 'PUBLISHED_VERIFIED',
          createdAt: '2026-08-10T10:00:00+08:00'
        }
      ]
    })
    const wrapper = mount(QualityView)
    await flushPromises()
    expect(wrapper.text()).toContain('预警')
    expect(wrapper.text()).toContain('7.00000000')
  })

  it('sources renders routes and honest PENDING entries', async () => {
    const wrapper = mount(SourcesView)
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('中国人民银行官网')
    expect(wrapper.text()).toContain('PENDING')
  })
})
