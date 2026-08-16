<script setup lang="ts">
import type { ItemCard } from '../types/dashboard'
import StatusBadge from './StatusBadge.vue'

defineProps<{ card: ItemCard }>()
</script>

<template>
  <section class="card">
    <div class="card-head">
      <span class="card-name">{{ card.displayName }}</span>
      <StatusBadge :status="card.quality.status" />
    </div>
    <div class="card-value">{{ card.latestValue ?? '—' }}</div>
    <div class="card-meta">
      <span>单位 {{ card.unit ?? '—' }}</span>
      <span>业务日期 {{ card.businessDate ?? '—' }}</span>
    </div>
    <div class="card-meta">来源：{{ card.source.actualSourceName ?? '—' }}</div>
    <div class="card-meta">
      完整率：{{ card.completeness ?? '—' }}
    </div>
    <div class="card-meta">
      路线：{{ card.source.routeDecision ?? '—' }}
      <span v-if="card.source.fallbackReason" class="muted">
        （{{ card.source.fallbackReason }}）
      </span>
    </div>
    <div class="card-meta">
      校验：{{ card.quality.validationVersion ?? '—' }}
      <span v-if="card.quality.stale" class="stale">STALE</span>
    </div>
    <div v-if="card.aggregateSummary" class="card-meta">
      聚合摘要：{{ card.aggregateSummary.grain }} {{ card.aggregateSummary.periodStart }}~
      {{ card.aggregateSummary.periodEnd }} = {{ card.aggregateSummary.value }}
      {{ card.aggregateSummary.unit ?? '' }}
    </div>
    <div v-if="card.warningSummary" class="card-warn">⚠ {{ card.warningSummary }}</div>
  </section>
</template>

<style scoped>
.card {
  background: #fff;
  border: 1px solid var(--sm-border);
  border-radius: 8px;
  padding: 14px 16px;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-name {
  font-weight: 600;
}
.card-value {
  font-size: 26px;
  font-variant-numeric: tabular-nums;
  margin: 8px 0 4px;
  color: var(--sm-accent);
}
.card-meta {
  font-size: 12px;
  color: var(--sm-muted);
  margin-top: 2px;
}
.stale {
  color: var(--sm-bad);
  font-weight: 600;
}
.card-warn {
  margin-top: 8px;
  font-size: 12px;
  color: var(--sm-warn);
  border-top: 1px dashed var(--sm-border);
  padding-top: 6px;
}
</style>
