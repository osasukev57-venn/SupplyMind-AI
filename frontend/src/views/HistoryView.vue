<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchHistory, fetchMetrics } from '../api/dashboard'
import type { HistoryResponse, MetricsResponse } from '../types/dashboard'
import TrendChart from '../components/TrendChart.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { evidenceIssueMessage, grainLabel, sourceDisplayName } from '../lib/labels'

const items = ref<{ itemId: string; displayName: string }[]>([])
const itemId = ref('')
const grain = ref<'daily' | 'month' | 'quarter' | 'halfyear' | 'year'>('daily')
const from = ref('2026-01-01')
const to = ref('2026-12-31')
const history = ref<HistoryResponse | null>(null)
const metrics = ref<MetricsResponse | null>(null)
const error = ref<string | null>(null)

onMounted(async () => {
  // The history selector lists ALL configured items INCLUDING stopped ones - a stopped
  // target stays selectable for historical queries and its history is never deleted.
  const config = await import('../api/config').then((m) => m.fetchConfigItems())
  if (config) {
    items.value = config.items.map((item) => ({
      itemId: item.itemId,
      displayName: item.displayName
    }))
    if (items.value.length > 0) {
      itemId.value = items.value[0].itemId
      await load()
    }
  }
})

async function load(): Promise<void> {
  error.value = null
  if (grain.value === 'daily') {
    history.value = await fetchHistory(itemId.value, from.value, to.value)
    metrics.value = null
    if (history.value === null) error.value = '历史数据暂时不可用，请稍后重试'
  } else {
    const fromYear = Number(from.value.slice(0, 4))
    const toYear = Number(to.value.slice(0, 4))
    if (fromYear > toYear) {
      error.value = '开始日期不能晚于结束日期'
      metrics.value = null
      return
    }
    metrics.value = await fetchMetrics(itemId.value, grain.value, fromYear, toYear)
    history.value = null
    if (metrics.value === null) error.value = '聚合数据暂时不可用，请稍后重试'
  }
}
</script>

<template>
  <div>
    <header class="page-head">
      <div class="page-eyebrow">监测</div>
      <h1 class="page-title">历史趋势</h1>
      <p class="page-desc">按日期范围与粒度查看历史走势和聚合结果。趋势图只绘制系统返回的数据点，缺失区间不做插值。</p>
    </header>

    <section class="section">
      <div class="section-body">
        <div class="controls">
          <select v-model="itemId" @change="load">
            <option v-for="item in items" :key="item.itemId" :value="item.itemId">
              {{ item.displayName }}
            </option>
          </select>
          <input v-model="from" type="date" @change="load" />
          <span class="muted">至</span>
          <input v-model="to" type="date" @change="load" />
          <select v-model="grain" @change="load">
            <option value="daily">按日</option>
            <option value="month">按月</option>
            <option value="quarter">按季</option>
            <option value="halfyear">按半年</option>
            <option value="year">按年</option>
          </select>
        </div>
      </div>
    </section>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <section v-if="grain === 'daily' && history" class="section">
      <div class="section-head">
        <h2>每日趋势</h2>
        <span class="muted">{{ history.points.length }} 个数据点，截至 {{ history.dataThrough ?? '—' }}</span>
      </div>
      <div class="section-body">
        <TrendChart :chart="history.chart" />
      </div>
      <div class="section-body flat">
        <div class="table-scroll">
          <table class="sm-table">
            <thead>
              <tr>
                <th>业务日期</th>
                <th>数值</th>
                <th>单位</th>
                <th>数据来源</th>
                <th>校验状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="point in history.points" :key="point.businessDate">
                <td class="num">{{ point.businessDate }}</td>
                <td class="num">{{ point.value }}</td>
                <td>{{ point.unit ?? '—' }}</td>
                <td>{{ sourceDisplayName(point.actualSourceName) }}</td>
                <td><StatusBadge :status="point.validationStatus" /></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-for="(issue, i) in history.evidenceIssues" :key="i" class="muted warn-line">
          {{ issue.status === 'MISSING' ? '缺失' : '损坏' }}：{{ evidenceIssueMessage(issue.status) }}
          （期间：{{ issue.periods.join('；') }}，未插值）
        </div>
      </div>
    </section>

    <section v-if="grain !== 'daily' && metrics" class="section">
      <div class="section-head">
        <h2>{{ grainLabel(metrics.grain) }}</h2>
        <span class="muted">{{ metrics.rows.length }} 行，{{ metrics.fromYear }}~{{ metrics.toYear }}</span>
      </div>
      <div class="section-body flat">
        <div class="table-scroll">
          <table class="sm-table">
            <thead>
              <tr>
                <th>期间起</th>
                <th>期间止</th>
                <th>数值</th>
                <th>单位</th>
                <th>数据来源</th>
                <th>校验状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, i) in metrics.rows" :key="i">
                <td class="num">{{ row.periodStart }}</td>
                <td class="num">{{ row.periodEnd }}</td>
                <td class="num">{{ row.value }}</td>
                <td>{{ row.unit ?? '—' }}</td>
                <td>{{ sourceDisplayName(row.actualSourceName) }}</td>
                <td><StatusBadge :status="row.validationStatus" /></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-for="(issue, i) in metrics.evidenceIssues" :key="i" class="muted warn-line">
          {{ issue.status === 'MISSING' ? '缺失' : '损坏' }}：{{ evidenceIssueMessage(issue.status) }}
          （期间：{{ issue.periods.join('；') }}）
        </div>
      </div>
    </section>

    <div v-if="!history && !metrics && !error" class="empty-state">请选择监测项和日期范围</div>
  </div>
</template>

<style scoped>
.controls {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}
.controls select,
.controls input {
  font-family: inherit;
  font-size: 13.5px;
  padding: 7px 9px;
  border: 1px solid var(--sm-border-strong);
  border-radius: var(--sm-radius);
  background: var(--sm-surface);
  color: var(--sm-text);
}
.controls select:focus,
.controls input:focus {
  outline: none;
  border-color: var(--sm-focus);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sm-focus) 22%, transparent);
}
.warn-line {
  margin-top: 10px;
  padding: 0 16px 12px;
}
</style>
