import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

vi.mock('../../api/warning', () => ({
  fetchWarnings: vi.fn(),
  fetchWarningRules: vi.fn(),
  evaluateAllWarningRules: vi.fn(),
  updateWarningRule: vi.fn(),
  resetWarningRule: vi.fn(),
  acknowledgeWarning: vi.fn(),
  evaluateWarning: vi.fn()
}))

import WarningView from '../WarningView.vue'
import {
  acknowledgeWarning,
  evaluateAllWarningRules,
  fetchWarningRules,
  fetchWarnings,
  resetWarningRule,
  updateWarningRule
} from '../../api/warning'
import type { RuleEvaluationView, RuleView, WarningView as WarningViewType } from '../../types/warning'

const rule: RuleView = {
  itemId: 'MAT.ADC12.SMM',
  displayName: 'ADC12 铝合金锭',
  actualSourceName: '公开市场来源',
  unit: '元/吨',
  rateKind: 'material',
  monitorEnabled: true,
  ruleId: 'default-warning-MAT.ADC12.SMM',
  ruleVersion: 'warning-relative-change-v1',
  lookbackDays: 7,
  threshold: '0.05',
  thresholdPercent: '5',
  direction: 'BOTH',
  enabled: true,
  origin: 'SYSTEM_DEFAULT',
  description: '系统通用材料预警模板'
}

const warning: WarningViewType = {
  warningId: 'w1',
  ruleId: rule.ruleId,
  ruleVersion: rule.ruleVersion,
  itemId: rule.itemId,
  grain: 'day',
  periodStart: '2026-08-17',
  periodEnd: '2026-08-24',
  threshold: '0.05',
  currentValue: '24900.00',
  baselineValue: '23000.00',
  riskLevel: 'HIGH',
  evidenceRefs: ['processed/daily/MAT.ADC12.SMM/2026-08.csv'],
  dataStatus: 'PUBLISHED_VERIFIED',
  evaluatedAt: '2026-08-24T02:00:00+08:00',
  demoRule: false,
  ruleDescription: '系统通用材料预警模板',
  acknowledged: false,
  ackRef: null
}

const evaluation: RuleEvaluationView = {
  rule,
  status: 'TRIGGERED',
  message: '当前变化已达到预警阈值',
  currentBusinessDate: '2026-08-24',
  currentValue: '24900.00',
  baselineBusinessDate: '2026-08-17',
  baselineValue: '23000.00',
  changeRatio: '0.082608695652',
  changePercent: '8.2608695652',
  unit: '元/吨',
  warning
}

describe('WarningView formal rule closure', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchWarningRules).mockResolvedValue({ rules: [rule] })
    vi.mocked(evaluateAllWarningRules).mockResolvedValue([evaluation])
    vi.mocked(fetchWarnings).mockResolvedValue({
      itemId: rule.itemId,
      from: '2026-06-01',
      to: '2026-08-25',
      warnings: [warning]
    })
  })

  it('automatically evaluates and renders the usable system default without frontend calculation', async () => {
    const wrapper = mount(WarningView, { attachTo: document.body })
    await flushPromises()
    await flushPromises()
    expect(fetchWarningRules).toHaveBeenCalled()
    expect(evaluateAllWarningRules).toHaveBeenCalled()
    expect(wrapper.text()).toContain('系统默认')
    expect(wrapper.text()).toContain('7 个自然日')
    expect(wrapper.text()).toContain('5%')
    expect(wrapper.text()).toContain('8.2608695652%')
    expect(wrapper.text()).toContain('已触发')
    expect(wrapper.text()).not.toContain('规则试算')
    wrapper.unmount()
  })

  it('saves a per-item percentage override and evaluates it immediately', async () => {
    const customRule = { ...rule, threshold: '0.04', thresholdPercent: '4', origin: 'USER_CUSTOM' as const }
    vi.mocked(updateWarningRule).mockResolvedValue({
      rule: customRule,
      evaluation: { ...evaluation, rule: customRule }
    })
    const wrapper = mount(WarningView, { attachTo: document.body })
    await flushPromises()
    const thresholdInput = wrapper.find('input[inputmode="decimal"]')
    await thresholdInput.setValue('4')
    await wrapper.findAll('button').find(button => button.text() === '保存并立即评估')?.trigger('click')
    await flushPromises()
    expect(updateWarningRule).toHaveBeenCalledWith(rule.itemId, expect.objectContaining({
      lookbackDays: 7,
      thresholdPercent: '4',
      direction: 'BOTH',
      enabled: true
    }))
    expect(wrapper.text()).toContain('自定义规则并立即完成评估')
    wrapper.unmount()
  })

  it('restores the backend system default', async () => {
    vi.mocked(resetWarningRule).mockResolvedValue({ rule, evaluation })
    const wrapper = mount(WarningView, { attachTo: document.body })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '恢复系统默认')?.trigger('click')
    await flushPromises()
    expect(resetWarningRule).toHaveBeenCalledWith(rule.itemId)
    expect(wrapper.text()).toContain('系统默认规则并立即完成评估')
    wrapper.unmount()
  })

  it('acknowledges a formal immutable warning with a separate disposition note', async () => {
    vi.mocked(acknowledgeWarning).mockResolvedValue({
      warningId: 'w1',
      warningRef: 'warning/2026-08/w1.json',
      warningFileSha256: 'a'.repeat(64),
      status: 'ACKNOWLEDGED',
      acknowledgedAt: '2026-08-25T02:00:00+08:00',
      dispositionNote: '已核实并处理'
    })
    const wrapper = mount(WarningView, { attachTo: document.body })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '确认处理')?.trigger('click')
    await wrapper.find('textarea').setValue('已核实并处理')
    await wrapper.findAll('button').find(button => button.text() === '确认')?.trigger('click')
    await flushPromises()
    expect(acknowledgeWarning).toHaveBeenCalledWith(rule.itemId, 'w1', '已核实并处理')
    expect(wrapper.text()).toContain('已确认 w1')
    wrapper.unmount()
  })

  it('fails honestly when formal rule service is unavailable', async () => {
    vi.mocked(fetchWarningRules).mockResolvedValue(null)
    const wrapper = mount(WarningView, { attachTo: document.body })
    await flushPromises()
    expect(wrapper.text()).toContain('预警规则暂时不可用')
    wrapper.unmount()
  })
})
