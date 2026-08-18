<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchQuality } from '../api/dashboard'
import type { QualityResponse } from '../types/dashboard'
import StatusBadge from '../components/StatusBadge.vue'
import { evidenceIssueMessage, providerLabel, sourceDisplayName } from '../lib/labels'

const itemId = ref('')
const items = ref<{ itemId: string; displayName: string }[]>([])
const quality = ref<QualityResponse | null>(null)
const error = ref<string | null>(null)

onMounted(async () => {
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
  quality.value = await fetchQuality(itemId.value, '2026-01-01', '2026-12-31')
  if (quality.value === null) error.value = '质量数据暂时不可用，请稍后重试'
}
</script>

<template>
  <div>
    <header class="page-head">
      <div class="page-eyebrow">监测</div>
      <h1 class="page-title">数据质量</h1>
      <p class="page-desc">查看校验状态、预警记录与证据完整性。所有状态均由系统判定并直接展示。</p>
    </header>

    <section class="section">
      <div class="section-body">
        <div class="controls">
          <select v-model="itemId" @change="load">
            <option v-for="item in items" :key="item.itemId" :value="item.itemId">
              {{ item.displayName }}
            </option>
          </select>
        </div>
      </div>
    </section>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <section v-if="quality" class="section">
      <div class="section-head">
        <h2>校验记录</h2>
        <StatusBadge :status="quality.latestStatus" />
      </div>
      <div class="section-body flat">
        <div class="table-scroll">
          <table class="sm-table">
            <thead>
              <tr>
                <th>业务日期</th>
                <th>数值</th>
                <th>数据来源</th>
                <th>接入方式</th>
                <th>校验状态</th>
                <th>校验版本</th>
                <th>完整率</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in quality.rows" :key="row.businessDate">
                <td class="num">{{ row.businessDate }}</td>
                <td class="num">{{ row.value }}</td>
                <td>{{ sourceDisplayName(row.actualSourceName) }}</td>
                <td>{{ providerLabel(row.providerType) }}</td>
                <td><StatusBadge :status="row.validationStatus" /></td>
                <td class="id-cell">{{ row.validationVersion ?? '—' }}</td>
                <td class="num">{{ row.completeness }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="quality.rows.length === 0" class="empty-state">该区间暂无已发布数据</div>
      </div>
    </section>

    <section v-if="quality && quality.warnings.length > 0" class="section">
      <div class="section-head">
        <h2>预警记录</h2>
        <span class="muted">{{ quality.warnings.length }} 条</span>
      </div>
      <div class="section-body flat">
        <div class="table-scroll">
          <table class="sm-table">
            <thead>
              <tr>
                <th>预警编号</th>
                <th>期间</th>
                <th>当前值</th>
                <th>阈值</th>
                <th>风险等级</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="w in quality.warnings" :key="w.warningId">
                <td class="id-cell">{{ w.warningId }}</td>
                <td>{{ w.periodStart }} ~ {{ w.periodEnd }}</td>
                <td class="num">{{ w.value }}</td>
                <td class="num">{{ w.threshold }}</td>
                <td><StatusBadge :status="w.riskLevel ?? 'UNKNOWN'" /></td>
                <td>{{ w.status ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <section v-if="quality" class="section">
      <div class="section-head">
        <h2>证据完整性</h2>
      </div>
      <div class="section-body">
        <div v-for="(issue, i) in quality.evidenceIssues" :key="i" class="muted warn-line">
          {{ issue.status === 'MISSING' ? '缺失' : '损坏' }}：{{ evidenceIssueMessage(issue.status) }}
          （期间：{{ issue.periods.join('；') }}）
        </div>
        <div v-if="quality.evidenceIssues.length === 0" class="muted">引用的数据文件全部通过完整性校验</div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.controls select {
  font-family: inherit;
  font-size: 13.5px;
  padding: 7px 9px;
  border: 1px solid var(--sm-border-strong);
  border-radius: var(--sm-radius);
  background: var(--sm-surface);
  color: var(--sm-text);
}
.controls select:focus {
  outline: none;
  border-color: var(--sm-focus);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sm-focus) 22%, transparent);
}
.warn-line {
  margin-bottom: 6px;
}
</style>
