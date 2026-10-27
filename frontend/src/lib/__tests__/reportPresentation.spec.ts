import { describe, expect, it } from 'vitest'
import { factSummaryForDisplay } from '../reportPresentation'

const footer = '当前为 Java 事实摘要；云端语言服务未采用，所有数值均直接来自标准事实，未在回答阶段重算。'

describe('saved Java summary presentation', () => {
  it.each(['', ' 该监测项为模拟数据，不代表真实市场风险。'])('neutralizes only the known template footer, preserving facts and notice %s', (notice) => {
    const facts = '最新25500.00元/吨；两组均值24800.00和23200.00；预警HIGH，已确认。 '
    const original = facts + footer + notice
    const display = factSummaryForDisplay(original)
    expect(display).toBe(facts + '当前为 Java 事实摘要；所有数值均直接来自标准事实，未在回答阶段重算。' + notice)
    expect(original).toContain('云端语言服务未采用')
  })

  it.each(['', '云端语言服务未采用，因为没有可用事实。', '普通事实摘要25500.00', footer + ' 后续还有其他业务文字。'])('does not broadly rewrite arbitrary text %s', (text) => {
    expect(factSummaryForDisplay(text)).toBe(text)
  })
  it.each([null, undefined])('supports absent legacy summaries %s', (text) => {
    expect(factSummaryForDisplay(text)).toBe('')
  })
})
