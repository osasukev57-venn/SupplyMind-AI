<script setup lang="ts">
import type { ItemCard } from '../types/dashboard'
import StatusBadge from './StatusBadge.vue'
import { fallbackReasonLabel, routeLabel } from '../lib/labels'

defineProps<{ card: ItemCard }>()
</script>

<template>
  <section class="card">
    <div class="card-head">
      <div class="card-title">
        <div class="card-name">{{ card.displayName }}</div>
        <div class="id-cell muted">{{ card.itemId }}</div>
      </div>
      <StatusBadge :status="card.quality.status" />
    </div>
    <div class="card-value">{{ card.latestValue ?? '暂无数据' }}</div>
    <div class="card-meta">
      <span>单位 {{ card.unit ?? '—' }}</span>
      <span>业务日期 {{ card.businessDate ?? '—' }}</span>
    </div>
    <dl class="card-details">
      <div><dt>数据来源</dt><dd>{{ card.source.actualSourceName ?? '—' }}</dd></div>
      <div><dt>完整率</dt><dd>{{ card.completeness ?? '—' }}</dd></div>
      <div>
        <dt>采集路线</dt>
        <dd>
          {{ routeLabel(card.source.routeDecision) }}
          <span v-if="card.source.fallbackReason" class="muted">（{{ fallbackReasonLabel(card.source.fallbackReason) }}）</span>
        </dd>
      </div>
      <div><dt>校验版本</dt><dd>{{ card.quality.validationVersion ?? '—' }}</dd></div>
    </dl>
    <div v-if="card.quality.stale" class="stale-line">数据已过期</div>
    <div v-if="card.aggregateSummary" class="card-meta">
      本月均值：{{ card.aggregateSummary.value }} {{ card.aggregateSummary.unit ?? '' }}
      （{{ card.aggregateSummary.periodStart }} ~ {{ card.aggregateSummary.periodEnd }}）
    </div>
    <div v-if="card.warningSummary" class="card-warn">{{ card.warningSummary }}</div>
  </section>
</template>

<style scoped>
.card {
  background: var(--sm-surface);
  border: 1px solid var(--sm-border);
  border-radius: var(--sm-radius);
  padding: 14px 16px;
  box-shadow: var(--sm-shadow);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}
.card-title { min-width: 0; }
.card-name {
  font-weight: 650;
  font-size: 13.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-value {
  font-family: var(--sm-font-mono);
  font-size: 26px;
  font-variant-numeric: tabular-nums;
  margin: 6px 0 2px;
  color: var(--sm-accent);
  word-break: break-word;
}
.card-meta {
  font-size: 12px;
  color: var(--sm-muted);
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.card-details {
  margin: 6px 0 0;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 2px 12px;
  font-size: 12px;
}
.card-details dt {
  color: var(--sm-muted);
}
.card-details dd {
  margin: 0;
  color: var(--sm-text-2);
  word-break: break-word;
}
.id-cell {
  font-family: var(--sm-font-mono);
  font-size: 11px;
}
.stale-line {
  color: var(--sm-bad);
  font-size: 12px;
  font-weight: 600;
}
.card-warn {
  margin-top: 6px;
  font-size: 12px;
  color: var(--sm-warn);
  border-top: 1px dashed var(--sm-border);
  padding-top: 6px;
}
</style>
