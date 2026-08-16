<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchHistory, fetchMetrics } from '../api/dashboard'
import type { HistoryResponse, MetricsResponse } from '../types/dashboard'
import TrendChart from '../components/TrendChart.vue'
import StatusBadge from '../components/StatusBadge.vue'

const items = ref<{ itemId: string; displayName: string }[]>([])
const itemId = ref('')
const grain = ref<'daily' | 'month' | 'quarter' | 'halfyear' | 'year'>('daily')
const from = ref('2026-01-01')
const to = ref('2026-12-31')
const history = ref<HistoryResponse | null>(null)
const metrics = ref<MetricsResponse | null>(null)
const error = ref<string | null>(null)

onMounted(async () => {
  const overview = await import('../api/dashboard').then((m) => m.fetchOverview())
  if (overview) {
    items.value = overview.items.map((card) => ({
      itemId: card.itemId,
      displayName: card.displayName
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
    if (history.value === null) error.value = '历史数据不可用'
  } else {
    metrics.value = await fetchMetrics(itemId.value, grain.value, 2026, 2026)
    history.value = null
    if (metrics.value === null) error.value = '聚合数据不可用'
  }
}
</script>

<template>
  <div>
    <div class="panel">
      <h2>历史趋势 / 聚合</h2>
      <div class="controls">
        <select v-model="itemId" @change="load">
          <option v-for="item in items" :key="item.itemId" :value="item.itemId">
            {{ item.displayName }}
          </option>
        </select>
        <input v-model="from" type="date" @change="load" />
        <input v-model="to" type="date" @change="load" />
        <select v-model="grain" @change="load">
          <option value="daily">日（daily）</option>
          <option value="month">月（month）</option>
          <option value="quarter">季（quarter）</option>
          <option value="halfyear">半年（halfyear）</option>
          <option value="year">年（year）</option>
        </select>
      </div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <div v-if="grain === 'daily' && history" class="panel">
      <h2>趋势（{{ history.points.length }} 个数据点，截至 {{ history.dataThrough ?? '—' }}）</h2>
      <TrendChart :points="history.points.map((p) => ({ businessDate: p.businessDate, value: p.value }))" />
      <table class="sm-table">
        <thead>
          <tr>
            <th>业务日期</th>
            <th>值（原字符串）</th>
            <th>单位</th>
            <th>来源</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="point in history.points" :key="point.businessDate">
            <td>{{ point.businessDate }}</td>
            <td>{{ point.value }}</td>
            <td>{{ point.unit ?? '—' }}</td>
            <td>{{ point.actualSourceName ?? '—' }}</td>
            <td><StatusBadge :status="point.validationStatus" /></td>
          </tr>
        </tbody>
      </table>
      <div v-if="history.missingRefs.length > 0" class="muted warn-line">
        缺失文件（未插值）：{{ history.missingRefs.join('；') }}
      </div>
      <div v-if="history.corruptRefs.length > 0" class="muted warn-line">
        损坏文件：{{ history.corruptRefs.join('；') }}
      </div>
    </div>

    <div v-if="grain !== 'daily' && metrics" class="panel">
      <h2>聚合（{{ metrics.grain }}，{{ metrics.rows.length }} 行）</h2>
      <table class="sm-table">
        <thead>
          <tr>
            <th>期间起</th>
            <th>期间止</th>
            <th>值（原字符串）</th>
            <th>单位</th>
            <th>来源</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in metrics.rows" :key="i">
            <td>{{ row.periodStart }}</td>
            <td>{{ row.periodEnd }}</td>
            <td>{{ row.value }}</td>
            <td>{{ row.unit ?? '—' }}</td>
            <td>{{ row.actualSourceName ?? '—' }}</td>
            <td><StatusBadge :status="row.validationStatus" /></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.controls {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: center;
}
.warn-line {
  margin-top: 8px;
}
</style>
