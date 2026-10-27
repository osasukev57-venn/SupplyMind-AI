/** Compatibility for the fixed Java template footer stored before cloud adoption.
 * Only presentation metadata is made neutral; facts and accepted model prose stay unchanged.
 * Adoption must always come from NarrativeResult, never from this summary string.
 */
export function factSummaryForDisplay(value: string | null | undefined): string {
  const summary = value ?? ''
  const legacy = '当前为 Java 事实摘要；云端语言服务未采用，所有数值均直接来自标准事实，未在回答阶段重算。'
  const offset = summary.lastIndexOf(legacy)
  if (offset < 0) return summary
  const tail = summary.slice(offset + legacy.length)
  // The known footer ends the summary, optionally followed by the simulation notice.
  if (tail.trim() && tail.trim() !== '该监测项为模拟数据，不代表真实市场风险。') return summary
  return summary.slice(0, offset) + '当前为 Java 事实摘要；所有数值均直接来自标准事实，未在回答阶段重算。' + tail
}

export function narrativeHeading(kind: string): string {
  return ({ OVERVIEW: '概览', TREND: '趋势解读', METRICS: '均值解读', WARNING: '预警与确认', QUALITY: '数据质量' } as Record<string, string>)[kind] ?? '补充解读'
}
