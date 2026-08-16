<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchQuality } from '../api/dashboard'
import type { QualityResponse } from '../types/dashboard'
import StatusBadge from '../components/StatusBadge.vue'

const itemId = ref('')
const items = ref<{ itemId: string; displayName: string }[]>([])
const quality = ref<QualityResponse | null>(null)
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
  quality.value = await fetchQuality(itemId.value, '2026-01-01', '2026-12-31')
  if (quality.value === null) error.value = '质量数据不可用'
}
</script>

<template>
  <div>
    <div class="panel">
      <h2>数据质量（校验 / 预警 / 证据）</h2>
      <select v-model="itemId" @change="load">
        <option v-for="item in items" :key="item.itemId" :value="item.itemId">
          {{ item.displayName }}
        </option>
      </select>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <div v-if="quality" class="panel">
      <h2>最新状态：<StatusBadge :status="quality.latestStatus" /></h2>
      <table class="sm-table">
        <thead>
          <tr>
            <th>业务日期</th>
            <th>值</th>
            <th>来源</th>
            <th>Provider</th>
            <th>校验状态</th>
            <th>校验版本</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in quality.rows" :key="row.businessDate">
            <td>{{ row.businessDate }}</td>
            <td>{{ row.value }}</td>
            <td>{{ row.actualSourceName ?? '—' }}</td>
            <td>{{ row.providerType ?? '—' }}</td>
            <td><StatusBadge :status="row.validationStatus" /></td>
            <td>{{ row.validationVersion ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="quality.rows.length === 0" class="muted">该区间无已发布数据</div>
    </div>

    <div v-if="quality && quality.warnings.length > 0" class="panel">
      <h2>预警（{{ quality.warnings.length }}）</h2>
      <table class="sm-table">
        <thead>
          <tr>
            <th>预警 ID</th>
            <th>期间</th>
            <th>当前值</th>
            <th>阈值</th>
            <th>风险</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="w in quality.warnings" :key="w.warningId">
            <td>{{ w.warningId }}</td>
            <td>{{ w.periodStart }} ~ {{ w.periodEnd }}</td>
            <td>{{ w.value }}</td>
            <td>{{ w.threshold }}</td>
            <td>{{ w.riskLevel ?? '—' }}</td>
            <td>{{ w.status ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="quality" class="panel">
      <h2>证据状态</h2>
      <div v-if="quality.evidenceMissingRefs.length > 0" class="muted">
        缺失：{{ quality.evidenceMissingRefs.join('；') }}
      </div>
      <div v-if="quality.evidenceCorruptRefs.length > 0" class="muted">
        损坏：{{ quality.evidenceCorruptRefs.join('；') }}
      </div>
      <div v-if="quality.evidenceMissingRefs.length === 0 && quality.evidenceCorruptRefs.length === 0"
        class="muted">引用文件全部通过清单校验</div>
    </div>
  </div>
</template>
