import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../../api/warning', () => ({
  fetchWarnings: vi.fn(),
  acknowledgeWarning: vi.fn(),
  evaluateWarning: vi.fn()
}))

import WarningView from '../WarningView.vue'
import { acknowledgeWarning, evaluateWarning, fetchWarnings } from '../../api/warning'
import type { WarningView as WarningViewType } from '../../types/warning'

const warning: WarningViewType = {
  warningId: 'w1',
  ruleId: 'demo-price-change-x',
  ruleVersion: 'demo-v1',
  itemId: 'MAT.ADC12.SMM',
  grain: 'month',
  periodStart: '2026-08-01',
  periodEnd: '2026-08-31',
  threshold: '0.05',
  currentValue: '0.087',
  baselineValue: '0.052',
  riskLevel: 'HIGH',
  evidenceRefs: ['processed/aggregate/MAT.ADC12.SMM/month/2026.csv'],
  dataStatus: 'PUBLISHED_VERIFIED',
  evaluatedAt: '2026-08-17T02:00:00+08:00',
  demoRule: true,
  ruleDescription: 'TEST/DEMO threshold - not a final business threshold (EXT-07 open)',
  acknowledged: false,
  ackRef: null
}

describe('WarningView (D8-T02)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchWarnings).mockResolvedValue({
      itemId: 'MAT.ADC12.SMM',
      from: '2026-07-01',
      to: '2026-08-31',
      warnings: [warning]
    })
  })

  it('renders the backend warning rows with demo markers', async () => {
    const wrapper = mount(WarningView, { attachTo: document.body })
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('w1')
    expect(wrapper.text()).toContain('demo-price-change-x')
    expect(wrapper.text()).toContain('0.087')
    expect(wrapper.text()).toContain('HIGH')
    expect(wrapper.text()).toContain('DEMO')
    wrapper.unmount()
  })

  it('acknowledge sends only the dispositionNote and shows the backend status', async () => {
    vi.mocked(acknowledgeWarning).mockResolvedValue({
      warningId: 'w1',
      warningRef: 'warning/2026-08/w1.json',
      warningFileSha256: 'a'.repeat(64),
      status: 'ACKNOWLEDGED',
      acknowledgedAt: '2026-08-17T02:00:00+08:00',
      dispositionNote: '已核实'
    })
    const wrapper = mount(WarningView, { attachTo: document.body })
    await flushPromises()
    await flushPromises()
    const confirmButtons = () =>
      wrapper.findAll('button').filter((button) => button.text() === '确认')
    await confirmButtons()[0].trigger('click')
    const textarea = wrapper.find('textarea')
    await textarea.setValue('已核实')
    await confirmButtons()[1].trigger('click')
    await flushPromises()

    expect(acknowledgeWarning).toHaveBeenCalledWith('MAT.ADC12.SMM', 'w1', '已核实')
    expect(wrapper.text()).toContain('已确认 w1')
    wrapper.unmount()
  })

  it('empty dispositionNote is rejected client-side without calling the backend', async () => {
    const wrapper = mount(WarningView, { attachTo: document.body })
    await flushPromises()
    await flushPromises()
    const confirmButtons = () =>
      wrapper.findAll('button').filter((button) => button.text() === '确认')
    await confirmButtons()[0].trigger('click')
    await confirmButtons()[1].trigger('click')
    await flushPromises()
    expect(acknowledgeWarning).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('处置备注不能为空')
    wrapper.unmount()
  })

  it('evaluate calls the backend demo rule and shows the trigger result', async () => {
    vi.mocked(evaluateWarning).mockResolvedValue({ status: 'TRIGGERED', warning })
    const wrapper = mount(WarningView, { attachTo: document.body })
    await flushPromises()
    const inputs = wrapper.findAll('input')
    await inputs[5].setValue('2026-08-01')
    await inputs[6].setValue('2026-08-31')
    await wrapper.findAll('button').find((button) => button.text().startsWith('执行'))?.trigger('click')
    await flushPromises()
    expect(evaluateWarning).toHaveBeenCalled()
    const request = vi.mocked(evaluateWarning).mock.calls[0][0]
    expect('demoRule' in request).toBe(false)
    expect(request.ruleKind).toBe('PRICE_CHANGE')
    expect(wrapper.text()).toContain('触发')
    wrapper.unmount()
  })

  it('backend failure shows an error banner instead of a white screen', async () => {
    vi.mocked(fetchWarnings).mockResolvedValue(null)
    const wrapper = mount(WarningView, { attachTo: document.body })
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('预警查询失败')
    wrapper.unmount()
  })
})
