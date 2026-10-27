import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api/agent', () => ({
  queryAgent: vi.fn(), readSavedReport: vi.fn()
}))

vi.mock('../../api/config', () => ({ fetchConfigItems: vi.fn().mockResolvedValue({ items: [{ itemId: 'MAT.AZ91D.SIMULATION', displayName: '模拟 AZ91D', enabled: true }] }) }))
import AgentView from '../AgentView.vue'
import { queryAgent, readSavedReport } from '../../api/agent'
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

async function fillRequiredFields(wrapper: ReturnType<typeof mountWithRouter>, question: string) {
  await wrapper.find('textarea').setValue(question)
  await wrapper.find('input').setValue('MAT.ADC12.SMM')
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
    await fillRequiredFields(wrapper, '\u5206\u6790 ADC12 \u8fd1\u671f\u4e0a\u6da8\u98ce\u9669')
    await wrapper.findAll('button').find((button) => button.text().includes('\u63d0\u4ea4\u5206\u6790'))?.trigger('click')
    await flushPromises()

    expect(queryAgent).toHaveBeenCalled()
    const request = vi.mocked(queryAgent).mock.calls[0][0]
    expect(request.question).toBe('\u5206\u6790 ADC12 \u8fd1\u671f\u4e0a\u6da8\u98ce\u9669')
    expect(request.mode).toBe('FORMAL')

    expect(wrapper.text()).toContain('ADC12 \u8fd1\u671f\u8d8b\u52bf\u5e73\u7a33')
    expect(wrapper.text()).toContain('Java 事实报告')
    expect(wrapper.text()).toContain('云端语言分析未被采用')
    expect(wrapper.text()).not.toContain('JAVA_TEMPLATE')
    expect(wrapper.text()).toContain('history.query')
    expect(wrapper.text()).toContain('19850.50')
    expect(wrapper.text()).toContain('\u8d8b\u52bf\u5e73\u7a33')
    expect(wrapper.text()).toContain('MAT.ADC12.SMM')
    wrapper.unmount()
  })

  it('renders evidence links as real RouterLink navigations from backend params', async () => {
    const wrapper = mountWithRouter()
    await fillRequiredFields(wrapper, '\u5206\u6790 ADC12')
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
    await fillRequiredFields(wrapper, '\u5206\u6790 ADC12')
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
    await fillRequiredFields(wrapper, '\u6d4b\u8bd5\u95ee\u9898')
    await wrapper.findAll('button').find((button) => button.text().includes('\u63d0\u4ea4\u5206\u6790'))?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('fallback: model did not select any tool')
    wrapper.unmount()
  })

  it('backend failure shows an error banner without white screen', async () => {
    vi.mocked(queryAgent).mockResolvedValue(null)
    const wrapper = mountWithRouter()
    await fillRequiredFields(wrapper, '\u6d4b\u8bd5\u95ee\u9898')
    await wrapper.findAll('button').find((button) => button.text().includes('\u63d0\u4ea4\u5206\u6790'))?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('\u670d\u52a1\u6682\u65f6\u4e0d\u53ef\u7528\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5')
    wrapper.unmount()
  })

  it('shows the specific 90 second timeout message and restores the submit button', async () => {
    vi.mocked(queryAgent).mockRejectedValue(
      new Error('智能分析处理超过 90 秒，后端可能仍在生成报告，请勿重复提交'))
    const wrapper = mountWithRouter()
    await fillRequiredFields(wrapper, '分析 ADC12')
    const submit = wrapper.findAll('button').find((button) => button.text().includes('提交分析'))
    await submit?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('智能分析处理超过 90 秒')
    expect(wrapper.text()).toContain('请勿重复提交')
    expect(wrapper.find('button.btn-primary').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })
  it('missing itemId is rejected client-side without calling the backend', async () => {
    const wrapper = mountWithRouter()
    await wrapper.find('textarea').setValue('分析走势')
    await wrapper.findAll('button').find((button) => button.text().includes('提交分析'))?.trigger('click')
    await flushPromises()
    expect(queryAgent).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请选择或填写监测项编号')
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
  it('shows resolved range, current rule acknowledgement and not-applicable warning validation', async () => {
    vi.mocked(queryAgent).mockResolvedValue({ ...response,
      scope: { itemIds: ['MAT.AZ91D.SIMULATION'], businessDate: null, periodStart: '2026-10-13', periodEnd: '2026-10-22', timezone: 'Asia/Shanghai' },
      risk: { riskLevel: 'HIGH', currentValue: '25500', baselineValue: '23250', threshold: '0.05', dataStatus: 'PUBLISHED_VERIFIED', demoRule: false,
        warningId: '2e973b219109dd627e241d9973c9ffe1', ruleId: 'custom-warning-MAT.AZ91D.SIMULATION', ruleVersion: 'warning-relative-change-v1-custom-r4',
        periodStart: '2026-10-15', periodEnd: '2026-10-22', acknowledgedStatus: 'ACKNOWLEDGED', acknowledgedAt: '2026-10-25T17:06:15+08:00' },
      facts: [{ factId: 'w1', statement: 'warning.riskLevel', value: 'HIGH', businessDate: '2026-10-22', period: '2026-10-15', validationStatus: null }]
    })
    const wrapper = mountWithRouter()
    await fillRequiredFields(wrapper, '说明月度均值和预警确认')
    await wrapper.find('button.btn-primary').trigger('click')
    await flushPromises()
    for (const text of ['2026-10-13', '2026-10-22', 'custom-r4', '2e973b219109dd627e241d9973c9ffe1', '已确认', '确认不代表风险消失', '不适用（预警证据单独核验）']) expect(wrapper.text()).toContain(text)
    expect(wrapper.text()).not.toContain('未知校验状态')
    expect(wrapper.text()).not.toContain('{"points":[]}')
    wrapper.unmount()
  })

  it('keeps verified facts and independent cloud sections visible in a partial result', async () => {
    vi.mocked(queryAgent).mockResolvedValue({ ...response, generatedBy: 'LLM_PARTIAL', simulation: true,
      narrative: { schemaVersion: 'ANALYSIS-NARRATIVE-V1', adoption: 'PARTIAL', cloudStatus: 'SUCCESS', attempts: 1,
        sections: [{sectionId:'s1',kind:'TREND',text:'观测区间呈上升趋势。',factIds:['f1'],evidenceRefs:[]}], recommendations: [],
        diagnostics: [{sectionId:'s2',code:'WRONG_AGGREGATE_GROUP'}], checkScope:'关键主张检查，不是任意语义证明' } })
    const wrapper = mountWithRouter()
    await fillRequiredFields(wrapper, '综合分析')
    await wrapper.find('button.btn-primary').trigger('click')
    await flushPromises()
    for (const text of ['部分采用', '观测区间呈上升趋势', 'Java 已验证事实', '模拟演示数据', 'WRONG_AGGREGATE_GROUP']) expect(wrapper.text()).toContain(text)
    expect(wrapper.text()).not.toContain('云端语言分析未被采用')
    expect(wrapper.text()).not.toContain('全部采用')
    const resultHeading = wrapper.findAll('.section-head').find(h => h.text().includes('分析结果'))
    expect(resultHeading?.text()).toContain('云端解读（部分采用）')
    expect(resultHeading?.text()).not.toContain('Java 事实报告')
    wrapper.unmount()
  })
  it('shows bounded source reasons alongside the category without exposing rejected prose', async () => {
    vi.mocked(queryAgent).mockResolvedValue({ ...response,
      narrative: { schemaVersion:'ANALYSIS-NARRATIVE-V1',adoption:'NONE',cloudStatus:'CONTENT_REJECTED',attempts:1,
        sections:[],recommendations:[],diagnostics:[{sectionId:'section-1',code:'UNSUPPORTED_SOURCE'},{sectionId:'section-1',code:'SOURCE_CONFLICT'}],checkScope:'来源断言受控检查' } })
    const wrapper=mountWithRouter()
    await fillRequiredFields(wrapper,'说明最新值和来源')
    await wrapper.find('button.btn-primary').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('UNSUPPORTED_SOURCE')
    expect(wrapper.text()).toContain('SOURCE_CONFLICT')
    expect(wrapper.text()).not.toContain('"diagnostics":')
    wrapper.unmount()
  })

  it('sends explicit dates and theme unchanged, and offers configured item selection', async () => {
    vi.mocked(queryAgent).mockResolvedValue(response)
    const wrapper = mountWithRouter()
    await flushPromises()
    expect(wrapper.find('datalist').text()).toContain('模拟 AZ91D')
    await fillRequiredFields(wrapper, '说明均值')
    await wrapper.find('input[aria-label="开始日期"]').setValue('2026-10-13')
    await wrapper.find('input[aria-label="结束日期"]').setValue('2026-10-22')
    await wrapper.find('select[aria-label="分析主题"]').setValue('METRICS')
    await wrapper.find('button.btn-primary').trigger('click')
    await flushPromises()
    expect(queryAgent).toHaveBeenCalledWith(expect.objectContaining({ startDate:'2026-10-13',endDate:'2026-10-22',theme:'METRICS' }),expect.anything())
    wrapper.unmount()
  })

  it('opens a persisted generation-time report without calling a model', async () => {
    vi.mocked(readSavedReport).mockResolvedValue({requestId:'req-old',reportRef:'report/2026-10/report-old.json',createdAt:'2026-10-26T10:00:00+08:00',generatedBy:'JAVA_TEMPLATE',javaSummary:'生成时均值24800.00',narrative:null,scope:{itemIds:['MAT.AZ91D.SIMULATION'],businessDate:null,periodStart:'2026-10-13',periodEnd:'2026-10-22',timezone:'Asia/Shanghai'}})
    const wrapper = mountWithRouter()
    await wrapper.find('input[aria-label="报告标识"]').setValue('report/2026-10/report-old.json')
    await wrapper.findAll('button').find(b=>b.text().includes('核验并打开报告'))?.trigger('click')
    await flushPromises()
    expect(readSavedReport).toHaveBeenCalledWith('report/2026-10/report-old.json')
    expect(queryAgent).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('生成时均值24800.00')
    wrapper.unmount()
  })

  it.each(['FULL', 'PARTIAL', 'NONE'] as const)('separates saved %s reports from the legacy Java fallback notice', async (adoption) => {
    const sections = adoption === 'NONE' ? [] : [
      { sectionId: 's-overview', kind: 'OVERVIEW', text: '模拟数据最新值为25500.00元/吨。', factIds: ['latest'], evidenceRefs: [] },
      { sectionId: 's-trend', kind: 'TREND', text: '观测区间价格呈上升趋势。', factIds: ['trend'], evidenceRefs: [] },
      { sectionId: 's-mean1', kind: 'METRICS', text: 'VERIFIED组均值为24800.00元/吨。', factIds: ['avg1'], evidenceRefs: [] },
      { sectionId: 's-mean2', kind: 'METRICS', text: 'VERIFIED_WITH_NOTICE组均值为23200.00元/吨。', factIds: ['avg2'], evidenceRefs: [] },
      { sectionId: 's-warning', kind: 'WARNING', text: '预警已确认，确认不代表风险消失。', factIds: ['warning'], evidenceRefs: [] },
      { sectionId: 's-quality', kind: 'QUALITY', text: '样本校验状态为4条已校验和4条有提示。', factIds: ['quality'], evidenceRefs: [] }
    ]
    vi.mocked(readSavedReport).mockResolvedValue({
      requestId: 'req-saved', reportRef: 'report/2026-10/report-saved.json', createdAt: '2026-10-27T10:01:12+08:00',
      generatedBy: adoption === 'FULL' ? 'LLM' : adoption === 'PARTIAL' ? 'LLM_PARTIAL' : 'JAVA_TEMPLATE',
      javaSummary: '根据正式数据，最新值为25500.00元/吨。 当前为 Java 事实摘要；云端语言服务未采用，所有数值均直接来自标准事实，未在回答阶段重算。 该监测项为模拟数据，不代表真实市场风险。',
      narrative: { schemaVersion: 'ANALYSIS-NARRATIVE-V1', adoption, cloudStatus: adoption === 'NONE' ? 'not_configured' : 'SUCCESS',
        sections, recommendations: adoption === 'NONE' ? [] : [{sectionId:'rec1',kind:'RECOMMENDATION',text:'建议继续观察后续发布数据。',factIds:['latest'],evidenceRefs:[]}],
        diagnostics: adoption === 'PARTIAL' ? [{sectionId:'rejected',code:'WRONG_AGGREGATE_GROUP'}] : [], attempts: adoption === 'NONE' ? 0 : 1, checkScope:'关键主张检查' },
      scope:{itemIds:['MAT.AZ91D.SIMULATION'],businessDate:null,periodStart:'2026-10-13',periodEnd:'2026-10-22',timezone:'Asia/Shanghai'}
    })
    const wrapper=mountWithRouter()
    await wrapper.find('input[aria-label="报告标识"]').setValue('report/2026-10/report-saved.json')
    await wrapper.findAll('button').find(b=>b.text().includes('核验并打开报告'))?.trigger('click')
    await flushPromises()
    const report=wrapper.find('[aria-label="已保存报告"]')
    expect(report.exists()).toBe(true)
    expect(report.text()).toContain('生成时事实')
    expect(report.text()).toContain('MAT.AZ91D.SIMULATION')
    expect(report.find('details').attributes('open')).toBeUndefined()
    expect(report.text()).toContain('25500.00元/吨')
    expect(report.text()).toContain('不代表真实市场风险')
    expect(report.text()).not.toContain('云端语言服务未采用')
    if (adoption === 'NONE') {
      expect(report.text()).toContain('未采用')
      expect(report.text()).toContain('尚未配置云模型')
      expect(report.findAll('.narrative-section')).toHaveLength(0)
      expect(report.text()).not.toContain('全部采用')
    } else {
      expect(report.findAll('.narrative-section')).toHaveLength(6)
      for(const label of ['概览','趋势解读','均值解读','预警与确认','数据质量','观察建议（模型建议）']) expect(report.text()).toContain(label)
      expect(report.text()).toContain('24800.00')
      expect(report.text()).toContain('23200.00')
      expect(report.text()).toContain(adoption === 'FULL' ? '全部采用' : '部分采用')
      if(adoption === 'PARTIAL') expect(report.text()).not.toContain('全部采用')
    }
    expect(queryAgent).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('distinguishes derived quality evidence from the underlying mixed sample states', async () => {
    vi.mocked(queryAgent).mockResolvedValue({ ...response, facts: [
      { factId:'sample-verified',statement:'history.query',value:'25500.00',businessDate:'2026-10-22',period:null,validationStatus:'VERIFIED' },
      { factId:'sample-notice',statement:'history.query',value:'23250.00',businessDate:'2026-10-15',period:null,validationStatus:'VERIFIED_WITH_NOTICE' },
      { factId:'derived-quality',statement:'quality.completeness',value:'100%',businessDate:null,period:'2026-10',validationStatus:'VERIFIED' }
    ] })
    const wrapper=mountWithRouter()
    await fillRequiredFields(wrapper,'说明数据质量和样本校验状态')
    await wrapper.find('button.btn-primary').trigger('click')
    await flushPromises()
    const row=wrapper.find('#derived-quality')
    expect(row.text()).toContain('派生质量指标（证据已核验，不代表样本校验状态）')
    expect(row.findComponent({ name:'StatusBadge' }).exists()).toBe(false)
    expect(wrapper.find('#sample-verified').findComponent({ name:'StatusBadge' }).exists()).toBe(true)
    expect(wrapper.find('#sample-notice').findComponent({ name:'StatusBadge' }).exists()).toBe(true)
    wrapper.unmount()
  })

})
