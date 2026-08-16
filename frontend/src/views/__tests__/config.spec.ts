import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

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

import ConfigView from '../ConfigView.vue'
import {
  addItem,
  createBackfillJob,
  fetchBackfillJobs,
  fetchCapabilities,
  fetchConfigHistory,
  fetchConfigItems,
  replaceItem,
  runBackfillJob,
  setEnabled
} from '../../api/config'
import type {
  AddItemRequest,
  BackfillJobView,
  ConfigView as ConfigViewType
} from '../../types/config'

const config: ConfigViewType = {
  schemaVersion: '1.0',
  configVersion: 2,
  mode: 'FORMAL',
  updatedAt: '2026-08-12T02:00:00+08:00',
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
      routeEffectiveAt: '2026-08-12T02:00:00+08:00',
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
      itemId: 'MAT.AZ91D.SMM',
      displayName: 'AZ91D镁合金锭（SMM意图）',
      enabled: true,
      sourceIntent: 'SMM',
      providerType: 'manual',
      accessMethod: 'manual',
      actualSourceName: '人工录入（Manual）',
      routeDecision: 'FALLBACK_MANUAL',
      fallbackReason: 'MANUAL_FALLBACK',
      routeEffectiveAt: '2026-08-12T02:00:00+08:00',
      supersedesItemId: null,
      externalCode: 'AZ91D',
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

const job: BackfillJobView = {
  jobId: 'backfill-FX.GBP.CNY.PBOC_MID-2026-08-01-2026-08-31',
  itemId: 'FX.GBP.CNY.PBOC_MID',
  fromDate: '2026-08-01',
  toDate: '2026-08-31',
  status: 'WAITING',
  completedPeriods: [],
  currentCheckpoint: null,
  failureReasons: [],
  configVersion: 2,
  createdAt: '2026-08-12T02:00:00+08:00',
  updatedAt: '2026-08-12T02:00:00+08:00'
}

describe('ConfigView (D8-T01)', () => {
  beforeEach(() => {
    vi.mocked(fetchConfigItems).mockResolvedValue(config)
    vi.mocked(fetchConfigHistory).mockResolvedValue([
      { configVersion: 1, verified: true, message: null },
      { configVersion: 2, verified: true, message: null }
    ])
    vi.mocked(fetchCapabilities).mockResolvedValue({
      providers: [
        {
          providerId: 'pboc-official-web',
          providerType: 'official_web',
          accessMethod: 'public_official_html',
          actualSourceName: '中国人民银行官网',
          supportsCurrentData: true,
          supportsHistoryData: false,
          supportedItemIds: ['FX.USD.CNY.PBOC_MID'],
          configuredRateKinds: ['人民币汇率中间价']
        }
      ]
    })
    vi.mocked(fetchBackfillJobs).mockResolvedValue({ jobs: [job] })
  })

  it('renders the current items with backend status and configVersion', async () => {
    const wrapper = mount(ConfigView, { attachTo: document.body })
    await flushPromises()
    expect(wrapper.text()).toContain('configVersion=2')
    expect(wrapper.text()).toContain('FX.USD.CNY.PBOC_MID')
    expect(wrapper.text()).toContain('MAT.AZ91D.SMM')
    expect(wrapper.text()).toContain('CNY/1 USD')
    expect(wrapper.text()).toContain('元/吨')
    expect(wrapper.text()).toContain('backfill-FX.GBP.CNY.PBOC_MID-2026-08-01-2026-08-31')
    expect(wrapper.text()).toContain('WAITING')
    wrapper.unmount()
  })

  it('disable button calls setEnabled and refreshes the panel', async () => {
    vi.mocked(setEnabled).mockResolvedValue({
      ...config,
      configVersion: 3,
      items: config.items.map((item) =>
        item.itemId === 'FX.USD.CNY.PBOC_MID' ? { ...item, enabled: false } : item
      )
    })
    const wrapper = mount(ConfigView, { attachTo: document.body })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '停用')?.trigger('click')
    await flushPromises()
    expect(setEnabled).toHaveBeenCalledWith('FX.USD.CNY.PBOC_MID', false)
    expect(wrapper.text()).toContain('停用 FX.USD.CNY.PBOC_MID 成功')
    wrapper.unmount()
  })

  it('add form sends a CONTROLLED request and shows the backend configVersion', async () => {
    vi.mocked(addItem).mockResolvedValue({
      config: { ...config, configVersion: 3 },
      backfillJobs: [job]
    })
    const wrapper = mount(ConfigView, { attachTo: document.body })
    await flushPromises()
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('FX.GBP.CNY.PBOC_MID')
    await inputs[1].setValue('英镑/人民币中间价')
    await inputs[4].setValue('CNY/1 GBP')
    await inputs[5].setValue('GBP')
    await wrapper.findAll('button').find((button) => button.text().startsWith('新增并激活'))?.trigger('click')
    await flushPromises()

    const request = vi.mocked(addItem).mock.calls[0][0] as AddItemRequest
    expect(request.itemId).toBe('FX.GBP.CNY.PBOC_MID')
    expect('configVersion' in request).toBe(false)
    expect('routeEffectiveAt' in request).toBe(false)
    expect('supersedesItemId' in request).toBe(false)
    expect(wrapper.text()).toContain('已激活 configVersion=3')
    wrapper.unmount()
  })

  it('replace sends oldItemId plus a controlled new item and shows the result', async () => {
    vi.mocked(replaceItem).mockResolvedValue({
      config: { ...config, configVersion: 4 },
      backfillJobs: []
    })
    const wrapper = mount(ConfigView, { attachTo: document.body })
    await flushPromises()
    const buttons = wrapper.findAll('button')
    const replaceButton = buttons.find((button) => button.text() === '替换（旧项停用，历史不删除）')
    const inputs = wrapper.findAll('input')
    await inputs[8].setValue('MAT.AZ91D.SMM')
    await inputs[9].setValue('MAT.REPL-01.SMM')
    await inputs[10].setValue('AZ91D替代材料（SMM意图）')
    await replaceButton?.trigger('click')
    await flushPromises()

    const request = vi.mocked(replaceItem).mock.calls[0][0]
    expect(request.oldItemId).toBe('MAT.AZ91D.SMM')
    expect(request.newItem.itemId).toBe('MAT.REPL-01.SMM')
    expect(request.newItem.routeDecision).toBe('fallback_manual')
    expect(wrapper.text()).toContain('已替换 MAT.AZ91D.SMM -> MAT.REPL-01.SMM')
    wrapper.unmount()
  })

  it('backfill run calls the backend and shows the new status', async () => {
    vi.mocked(runBackfillJob).mockResolvedValue({ ...job, status: 'SUCCEEDED' })
    const wrapper = mount(ConfigView, { attachTo: document.body })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '运行')?.trigger('click')
    await flushPromises()
    expect(runBackfillJob).toHaveBeenCalledWith(job.jobId)
    expect(wrapper.text()).toContain(`任务 ${job.jobId} 状态=SUCCEEDED`)
    wrapper.unmount()
  })

  it('create backfill job form calls createBackfillJob with itemId/from/to', async () => {
    vi.mocked(createBackfillJob).mockResolvedValue(job)
    const wrapper = mount(ConfigView, { attachTo: document.body })
    await flushPromises()
    const inputs = wrapper.findAll('input')
    await inputs[12].setValue('FX.GBP.CNY.PBOC_MID')
    await inputs[13].setValue('2026-08-01')
    await inputs[14].setValue('2026-08-31')
    await wrapper.findAll('button').find((button) => button.text() === '创建回填任务')?.trigger('click')
    await flushPromises()
    expect(createBackfillJob).toHaveBeenCalledWith('FX.GBP.CNY.PBOC_MID', '2026-08-01', '2026-08-31')
    wrapper.unmount()
  })

  it('API failure shows an error banner instead of a white screen', async () => {
    vi.mocked(fetchConfigItems).mockResolvedValue(null)
    const wrapper = mount(ConfigView, { attachTo: document.body })
    await flushPromises()
    expect(wrapper.text()).toContain('无监测项或后端不可用')
    wrapper.unmount()
  })
})
