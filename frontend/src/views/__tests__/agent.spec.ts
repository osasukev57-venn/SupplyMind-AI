import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api/agent', () => ({
  queryAgent: vi.fn()
}))

import AgentView from '../AgentView.vue'
import { queryAgent } from '../../api/agent'
import type { AgentQueryResponse } from '../../types/agent'

function mountWithRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/history', component: { template: '<div />' } },
      { path: '/warning', component: { template: '<div />' } },
      { path: '/quality', component: { template: '<div />' } }
    ]
  })
  return mount(AgentView, { attachTo: document.body, global: { plugins: [router] } })
}

const response: AgentQueryResponse = {
  requestId: 'req-1',
  answer: '\u0041DC12 \u8fd1\u671f\u8d8b\u52bf\u5e73\u7a33\uff0c\u672a\u89c1\u663e\u8457\u4e0a\u6da8\u98ce\u9669\u3002',
  llmStatus: 'UNAVAILABLE',
  degraded: true,
  degradeReason: 'TOOL_EXECUTION_REJECTED',
  toolTrace: [
    {
      invocationIndex: 0,
      toolName: 'history.query',
      toolVersion: 'v1',
      readOnly: true,
      input: '{"itemId":"MAT.ADC12.SMM"}',
      output: '{"points":[]}',
      status: 'SUCCESS',
      evidenceRefs: ['processed/daily/MAT.ADC12.SMM/2026-08.csv']
    }
  ],
  evidenceRefs: ['processed/daily/MAT.ADC12.SMM/2026-08.csv'],
  reportRef: 'report/2026-08/report-1.json',
  facts: [
    {
      factId: 'f1',
      statement: 'TREND',
      value: '19850.50',
      businessDate: '2026-08-10',
      period: '2026-08-01',
      validationStatus: 'VERIFIED'
    }
  ],
  generatedBy: 'JAVA_TEMPLATE',
  provider: null,
  model: null,
  scope: {
    itemIds: ['MAT.ADC12.SMM'],
    businessDate: '2026-08-10',
    periodStart: '2026-08-01',
    periodEnd: '2026-08-10',
    timezone: 'Asia/Shanghai'
  },
  limitations: ['fallback: model did not select any tool'],
  recommendations: ['\u5efa\u8bae\u5173\u6ce8\u4e0b\u5468\u91c7\u8d2d\u8282\u594f'],
  claims: [
    {
      claimId: 'claim-1',
      text: '\u8d8b\u52bf\u5e73\u7a33',
      evidenceRefs: ['processed/daily/MAT.ADC12.SMM/2026-08.csv']
    }
  ],
  dataThrough: '2026-08-10',
  evidenceLinks: [
    {
      evidenceId: 'e1',
      evidenceType: 'DAILY',
      itemId: 'MAT.ADC12.SMM',
      businessDate: '2026-08-10',
      periodStart: null,
      periodEnd: null,
      grain: 'daily',
      targetView: 'HISTORY',
      route: '/history',
      query: 'itemId=MAT.ADC12.SMM&from=2026-08-10&to=2026-08-10&grain=daily'
    },
    {
      evidenceId: 'e2',
      evidenceType: 'WARNING',
      itemId: 'MAT.ADC12.SMM',
      businessDate: null,
      periodStart: '2026-08-01',
      periodEnd: '2026-08-31',
      grain: null,
      targetView: 'WARNING',
      route: '/warning',
      query: 'itemId=MAT.ADC12.SMM&from=1900-01-01&to=2999-12-31'
    }
  ],
  calculationBasis: {
    validationVersion: 'material-basic-validation-v2',
    calculationVersion: 'arithmetic-mean-v1',
    calendarVersion: 'weekday-asia-shanghai-v1',
    configVersions: ['1']
  },
  risk: {
    riskLevel: 'HIGH',
    currentValue: '0.087',
    baselineValue: null,
    threshold: null,
    dataStatus: 'PUBLISHED_VERIFIED',
    demoRule: true
  }
}

describe('AgentView (D8-T03)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(queryAgent).mockResolvedValue(response)
  })

  it('renders the backend facts, tool timeline, claims and template label', async () => {
    const wrapper = mountWithRouter()
    const textarea = wrapper.find('textarea')
    await textarea.setValue('\u5206\u6790 ADC12 \u8fd1\u671f\u4e0a\u6da8\u98ce\u9669')
    await wrapper.findAll('button').find((button) => button.text().includes('\u63d0\u4ea4\u5206\u6790'))?.trigger('click')
    await flushPromises()

    expect(queryAgent).toHaveBeenCalled()
    const request = vi.mocked(queryAgent).mock.calls[0][0]
    expect(request.question).toBe('\u5206\u6790 ADC12 \u8fd1\u671f\u4e0a\u6da8\u98ce\u9669')
    expect(request.mode).toBe('FORMAL')

    expect(wrapper.text()).toContain('ADC12 \u8fd1\u671f\u8d8b\u52bf\u5e73\u7a33')
    // The report-source badge keeps the technical token (JAVA_TEMPLATE); the user-facing
    // degraded note uses product copy.
    expect(wrapper.text()).toContain('JAVA_TEMPLATE')
    expect(wrapper.text()).toContain('\u667a\u80fd\u5206\u6790\u670d\u52a1\u4e0d\u53ef\u7528')
    expect(wrapper.text()).toContain('\u672c\u5730\u53ef\u4fe1\u62a5\u544a')
    expect(wrapper.text()).toContain('history.query')
    expect(wrapper.text()).toContain('19850.50')
    expect(wrapper.text()).toContain('\u8d8b\u52bf\u5e73\u7a33')
    expect(wrapper.text()).toContain('MAT.ADC12.SMM')
    wrapper.unmount()
  })

  it('renders evidence links as real RouterLink navigations from backend params', async () => {
    const wrapper = mountWithRouter()
    await wrapper.find('textarea').setValue('\u5206\u6790 ADC12')
    await wrapper.findAll('button').find((button) => button.text().includes('\u63d0\u4ea4\u5206\u6790'))?.trigger('click')
    await flushPromises()

    const links = wrapper.findAll('a')
    expect(links.length).toBeGreaterThanOrEqual(2)
    const historyLink = links.find((link) => link.text().includes('\u5386\u53f2'))
    expect(historyLink?.attributes('href')).toBe(
      '/history?itemId=MAT.ADC12.SMM&from=2026-08-10&to=2026-08-10&grain=daily')
    const warningLink = links.find((link) => link.text().includes('\u9884\u8b66'))
    expect(warningLink?.attributes('href')).toBe(
      '/warning?itemId=MAT.ADC12.SMM&from=1900-01-01&to=2999-12-31')
    // M3: route parameters come from the backend DTO (query string is a single backend value).
    const hrefs = links.map((link) => link.attributes('href') ?? '')
    expect(hrefs.some((href) => href.startsWith('/history?'))).toBe(true)
    expect(hrefs.some((href) => href.startsWith('/warning?'))).toBe(true)
    wrapper.unmount()
  })

  it('renders recommendations and the calculation basis without computing anything', async () => {
    const wrapper = mountWithRouter()
    await wrapper.find('textarea').setValue('\u5206\u6790 ADC12')
    await wrapper.findAll('button').find((button) => button.text().includes('\u63d0\u4ea4\u5206\u6790'))?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('\u5efa\u8bae\u5173\u6ce8\u4e0b\u5468\u91c7\u8d2d\u8282\u594f')
    expect(wrapper.text()).toContain('material-basic-validation-v2')
    expect(wrapper.text()).toContain('arithmetic-mean-v1')
    expect(wrapper.text()).toContain('weekday-asia-shanghai-v1')
    // The calculation-basis section uses product labels, not raw field names.
    expect(wrapper.text()).toContain('\u8ba1\u7b97\u53e3\u5f84')
    expect(wrapper.text()).toContain('\u6821\u9a8c\u7248\u672c')
    expect(wrapper.text()).toContain('\u914d\u7f6e\u7248\u672c')
    expect(wrapper.text()).toContain('HIGH')
    expect(wrapper.text()).toContain('PUBLISHED_VERIFIED')
    expect(wrapper.text()).toContain('当前风险结果来自演示规则')
    // M3: no absolute paths / dataRoot in the page.
    expect(wrapper.text()).not.toContain('data-root')
    expect(wrapper.text()).not.toMatch(/[A-Za-z]:[\\/]/)
    wrapper.unmount()
  })

  it('shows the honest fallback limitation', async () => {
    const wrapper = mountWithRouter()
    await wrapper.find('textarea').setValue('\u6d4b\u8bd5\u95ee\u9898')
    await wrapper.findAll('button').find((button) => button.text().includes('\u63d0\u4ea4\u5206\u6790'))?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('fallback: model did not select any tool')
    wrapper.unmount()
  })

  it('backend failure shows an error banner without white screen', async () => {
    vi.mocked(queryAgent).mockResolvedValue(null)
    const wrapper = mountWithRouter()
    await wrapper.find('textarea').setValue('\u6d4b\u8bd5\u95ee\u9898')
    await wrapper.findAll('button').find((button) => button.text().includes('\u63d0\u4ea4\u5206\u6790'))?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('\u670d\u52a1\u6682\u65f6\u4e0d\u53ef\u7528\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5')
    wrapper.unmount()
  })

  it('shows the specific 90 second timeout message and restores the submit button', async () => {
    vi.mocked(queryAgent).mockRejectedValue(
      new Error('智能分析处理超过 90 秒，后端可能仍在生成报告，请勿重复提交'))
    const wrapper = mountWithRouter()
    await wrapper.find('textarea').setValue('分析 ADC12')
    const submit = wrapper.findAll('button').find((button) => button.text().includes('提交分析'))
    await submit?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('智能分析处理超过 90 秒')
    expect(wrapper.text()).toContain('请勿重复提交')
    expect(wrapper.find('button.btn-primary').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })
  it('empty question is rejected client-side without calling the backend', async () => {
    const wrapper = mountWithRouter()
    await wrapper.findAll('button').find((button) => button.text().includes('\u63d0\u4ea4\u5206\u6790'))?.trigger('click')
    await flushPromises()
    expect(queryAgent).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('\u8bf7\u8f93\u5165\u95ee\u9898')
    wrapper.unmount()
  })
})
