<script setup lang="ts">
import type { NarrativeResult } from '../types/agent'
import { narrativeHeading } from '../lib/reportPresentation'

defineProps<{ narrative: NarrativeResult; linkFacts?: boolean }>()

function unavailableReason(status: string): string {
  if (status === 'not_configured') return '尚未配置云模型'
  if (status === 'ANALYSIS_CONTEXT_LIMIT') return '分析范围超过模型上下文限制'
  if (status.includes('BUDGET') || status.includes('TIMEOUT')) return '云端分析超时'
  if (status.includes('CAPACITY')) return '分析服务繁忙'
  return '云端解读不可用或未通过检查'
}
</script>

<template>
  <section class="narrative-panel" aria-label="云端智能解读">
    <header class="narrative-head">
      <div>
        <p class="report-eyebrow">基于 Java 已核验事实</p>
        <h3>云端智能解读</h3>
      </div>
      <span class="adoption-badge" :class="'adoption-' + narrative.adoption.toLowerCase()">
        {{ narrative.adoption === 'FULL' ? '全部采用' : narrative.adoption === 'PARTIAL' ? '部分采用' : '未采用' }}
      </span>
    </header>
    <p v-if="narrative.adoption === 'FULL'" class="narrative-note">以下段落已通过结构、引用和关键主张检查。智能解读不代替业务判断。</p>
    <p v-else-if="narrative.adoption === 'PARTIAL'" class="narrative-note partial-note">部分段落未通过检查，以下仅展示独立合格段落；Java 事实仍保留。</p>
    <p v-else class="narrative-note">{{ unavailableReason(narrative.cloudStatus) }}；本报告使用 Java 事实摘要，未展示未通过检查的云端内容。</p>

    <div v-if="narrative.adoption !== 'NONE'" class="narrative-grid">
      <article v-for="section in narrative.sections" :key="section.sectionId" class="narrative-section">
        <header class="narrative-section-head">
          <h4>{{ narrativeHeading(section.kind) }}</h4>
          <span class="fact-count">{{ section.factIds.length }} 项事实依据</span>
        </header>
        <p class="narrative-text">{{ section.text }}</p>
        <details v-if="section.factIds.length" class="section-citations">
          <summary>查看事实引用</summary>
          <div class="fact-chips">
            <template v-for="id in section.factIds" :key="id">
              <a v-if="linkFacts" :href="'#' + id">{{ id }}</a>
              <span v-else>{{ id }}</span>
            </template>
          </div>
        </details>
      </article>
    </div>

    <section v-if="narrative.adoption !== 'NONE' && narrative.recommendations.length" class="recommendation-panel">
      <h4>观察建议（模型建议）</h4>
      <p class="recommendation-note">建议不等于已验证事实或业务决策。</p>
      <ul>
        <li v-for="recommendation in narrative.recommendations" :key="recommendation.sectionId">{{ recommendation.text }}</li>
      </ul>
    </section>
    <details class="cloud-check">
      <summary>云端检查状态</summary>
      <p>{{ narrative.checkScope }}</p>
      <ul v-if="narrative.diagnostics.length"><li v-for="diagnostic in narrative.diagnostics" :key="`${diagnostic.sectionId}:${diagnostic.code}`">{{ diagnostic.sectionId }}：{{ diagnostic.code }}</li></ul>
    </details>
  </section>
</template>

<style scoped>
.narrative-panel { color: var(--sm-text); }
.narrative-head, .narrative-section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.report-eyebrow { margin: 0 0 5px; font-size: 12px; color: var(--sm-text-2); }
.narrative-head h3 { margin: 0; font-size: 18px; font-weight: 700; }
.adoption-badge { flex-shrink: 0; padding: 5px 12px; border: 1px solid var(--sm-border); border-radius: 6px; font-size: 13px; font-weight: 650; }
.adoption-full { background: var(--sm-ok-bg); color: var(--sm-ok); }
.adoption-partial { background: var(--sm-warn-bg); color: var(--sm-warn); }
.adoption-none { background: var(--sm-surface); color: var(--sm-text-2); }
.narrative-note { margin: 12px 0 20px; line-height: 1.7; font-size: 13px; color: var(--sm-text-2); }
.partial-note { color: var(--sm-warn); }
.narrative-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.narrative-section { min-width: 0; margin: 0; padding: 20px; background: var(--sm-surface, #fff); border: 1px solid var(--sm-border); border-radius: 8px; }
.narrative-section h4, .recommendation-panel h4 { margin: 0; font-size: 15px; font-weight: 700; line-height: 1.5; }
.fact-count { color: var(--sm-text-2); font-size: 12px; white-space: nowrap; }
.narrative-text { margin: 14px 0 0; font-size: 14px; line-height: 1.9; overflow-wrap: anywhere; white-space: pre-wrap; }
.section-citations { margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--sm-border); }
summary { cursor: pointer; font-size: 12px; color: var(--sm-text-2); }
summary:focus-visible, a:focus-visible { outline: 2px solid var(--sm-focus, #1d6de3); outline-offset: 3px; }
.fact-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.fact-chips a, .fact-chips span { padding: 3px 7px; font-size: 12px; background: var(--sm-surface-2); border: 1px solid var(--sm-border); border-radius: 4px; color: var(--sm-accent); overflow-wrap: anywhere; }
.recommendation-panel { margin-top: 20px; padding: 18px 20px; border: 1px solid var(--sm-border); border-radius: 8px; background: var(--sm-surface-2); }
.recommendation-note { margin: 5px 0 12px; font-size: 12px; color: var(--sm-text-2); }
.recommendation-panel ul { margin: 0; padding-left: 20px; }
.recommendation-panel li { padding-left: 3px; margin: 8px 0; font-size: 14px; line-height: 1.8; overflow-wrap: anywhere; }
.cloud-check { margin-top: 20px; padding: 14px 0 0; border-top: 1px solid var(--sm-border); }
.cloud-check p, .cloud-check li { font-size: 12px; line-height: 1.8; color: var(--sm-text-2); overflow-wrap: anywhere; }
@media (max-width: 1100px) { .narrative-grid { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 600px) { .narrative-section { padding: 16px; } .narrative-section-head { align-items: flex-start; flex-direction: column; gap: 4px; } }
@media print { .narrative-section { break-inside: avoid; } .narrative-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
