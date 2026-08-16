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
  // D8-T04 (AT-UI-002): the history selector must list ALL configured items INCLUDING
  // disabled ones - a stopped target stays selectable for historical queries (H09/H06).
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
    if (history.value === null) error.value = '历史数据不可用'
  } else {
    // M2: the aggregate years come from the USER-SELECTED range - never a fixed year.
    const fromYear = Number(from.value.slice(0, 4))
    const toYear = Number(to.value.slice(0, 4))
    if (fromYear > toYear) {
      error.value = '开始日期不能晚于结束日期'
      metrics.value = null
      return
    }
    metrics.value = await fetchMetrics(itemId.value, grain.value, fromYear, toYear)
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
      <TrendChart :chart="history.chart" />
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
      <div v-for="(issue, i) in history.evidenceIssues" :key="i" class="muted warn-line">
        {{ issue.status === 'MISSING' ? '缺失' : '损坏' }}：{{ issue.reason }}
        （期间：{{ issue.periods.join('；') }}，未插值）
      </div>
    </div>

    <div v-if="grain !== 'daily' && metrics" class="panel">
      <h2>聚合（{{ metrics.grain }}，{{ metrics.rows.length }} 行，{{ metrics.fromYear }}~{{ metrics.toYear }}）</h2>
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
      <div v-for="(issue, i) in metrics.evidenceIssues" :key="i" class="muted warn-line">
        {{ issue.status === 'MISSING' ? '缺失' : '损坏' }}：{{ issue.reason }}
        （期间：{{ issue.periods.join('；') }}）
      </div>
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
