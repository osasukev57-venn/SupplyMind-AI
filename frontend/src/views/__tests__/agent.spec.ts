import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../../api/agent', () => ({
  queryAgent: vi.fn()
}))

import AgentView from '../AgentView.vue'
import { queryAgent } from '../../api/agent'
import type { AgentQueryResponse } from '../../types/agent'

const response: AgentQueryResponse = {
  requestId: 'req-1',
  answer: 'ADC12 近期趋势平稳，未见显著上行风险。',
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
  recommendations: [],
  claims: [
    {
      claimId: 'claim-1',
      text: '趋势平稳',
      evidenceRefs: ['processed/daily/MAT.ADC12.SMM/2026-08.csv']
    }
  ],
  dataThrough: '2026-08-10'
}

describe('AgentView (D8-T03)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(queryAgent).mockResolvedValue(response)
  })

  it('renders the backend facts, tool timeline, claims and template label', async () => {
    const wrapper = mount(AgentView, { attachTo: document.body })
    const textarea = wrapper.find('textarea')
    await textarea.setValue('分析 ADC12 近期上涨风险')
    await wrapper.findAll('button').find((button) => button.text().includes('提交分析'))?.trigger('click')
    await flushPromises()

    expect(queryAgent).toHaveBeenCalled()
    const request = vi.mocked(queryAgent).mock.calls[0][0]
    expect(request.question).toBe('分析 ADC12 近期上涨风险')
    expect(request.mode).toBe('FORMAL')

    expect(wrapper.text()).toContain('ADC12 近期趋势平稳')
    expect(wrapper.text()).toContain('JAVA_TEMPLATE')
    expect(wrapper.text()).toContain('Java 模板降级')
    expect(wrapper.text()).toContain('history.query')
    expect(wrapper.text()).toContain('19850.50')
    expect(wrapper.text()).toContain('趋势平稳')
    expect(wrapper.text()).toContain('MAT.ADC12.SMM')
    wrapper.unmount()
  })

  it('shows the honest fallback limitation', async () => {
    const wrapper = mount(AgentView, { attachTo: document.body })
    await wrapper.find('textarea').setValue('测试问题')
    await wrapper.findAll('button').find((button) => button.text().includes('提交分析'))?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('fallback: model did not select any tool')
    wrapper.unmount()
  })

  it('backend failure shows an error banner without white screen', async () => {
    vi.mocked(queryAgent).mockResolvedValue(null)
    const wrapper = mount(AgentView, { attachTo: document.body })
    await wrapper.find('textarea').setValue('测试问题')
    await wrapper.findAll('button').find((button) => button.text().includes('提交分析'))?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Agent 查询失败')
    wrapper.unmount()
  })

  it('empty question is rejected client-side without calling the backend', async () => {
    const wrapper = mount(AgentView, { attachTo: document.body })
    await wrapper.findAll('button').find((button) => button.text().includes('提交分析'))?.trigger('click')
    await flushPromises()
    expect(queryAgent).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入问题')
    wrapper.unmount()
  })
})
