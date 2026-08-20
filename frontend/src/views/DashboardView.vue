<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  fetchCurrentAcquisition,
  fetchOverview,
  refreshCurrentAcquisition
} from '../api/dashboard'
import type { CurrentAcquisitionStatus, OverviewResponse } from '../types/dashboard'
import ValueCard from '../components/ValueCard.vue'
import StatusBadge from '../components/StatusBadge.vue'

const data = ref<OverviewResponse | null>(null)
const acquisition = ref<CurrentAcquisitionStatus | null>(null)
const error = ref<string | null>(null)
const refreshPending = ref(false)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollAttempts = 0

const activeCount = computed(() => data.value?.items.length ?? 0)
const hasPublishedValue = computed(() =>
  data.value?.items.some((item) => item.latestValue !== null) ?? false
)

async function loadCurrentData() {
  const [overview, current] = await Promise.all([
    fetchOverview(),
    fetchCurrentAcquisition()
  ])
  data.value = overview
  acquisition.value = current
  error.value = overview === null && current?.state !== 'RUNNING'
    ? '总览数据暂时不可用，请稍后重试'
    : null

  if (current?.state === 'RUNNING' && pollAttempts < 60) {
    pollAttempts += 1
    pollTimer = setTimeout(loadCurrentData, 1500)
  }
}

async function requestCurrentRates() {
  refreshPending.value = true
  pollAttempts = 0
  acquisition.value = await refreshCurrentAcquisition()
  refreshPending.value = false
  await loadCurrentData()
}

onMounted(loadCurrentData)
onBeforeUnmount(() => {
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
  }
})
</script>

<template>
  <div>
    <header class="page-head">
      <div class="page-eyebrow">监测</div>
      <h1 class="page-title">
        总览
        <StatusBadge v-if="data?.mode" :status="data.mode" />
      </h1>
      <p class="page-desc">各监测项的最新值与状态。所有数值均由系统直接提供，未经前端加工。</p>
    </header>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="acquisition?.state === 'RUNNING'" class="acquisition-banner is-running">
      <strong>正在获取中国人民银行最新公布汇率</strong>
      <span>完成后将自动刷新美元与欧元数据，无需重启。</span>
    </div>
    <div v-else-if="acquisition?.state === 'FAILED'" class="acquisition-banner is-failed">
      <div>
        <strong>暂时无法获取中国人民银行公开汇率</strong>
        <span>已有历史数据仍可查看；网络恢复后可重新获取。</span>
      </div>
      <button class="action-btn" :disabled="refreshPending" @click="requestCurrentRates">
        {{ refreshPending ? '正在请求…' : '重新获取官方汇率' }}
      </button>
    </div>
    <div v-else-if="acquisition?.state === 'SUCCEEDED'" class="acquisition-banner is-success">
      <strong>官方汇率已更新</strong>
      <span>业务日期 {{ acquisition.businessDate }}，数据来自中国人民银行官网。</span>
    </div>
    <div v-if="data?.mode === 'DEMO'" class="demo-banner">
      当前为演示数据，仅用于功能体验，不用于正式业务判断
    </div>
    <div v-if="data === null && !error" class="empty-state">正在读取已存数据…</div>

    <div v-if="data" class="summary-strip">
      <div class="summary-item">
        <div class="summary-label">监测项</div>
        <div class="summary-value">{{ activeCount }}</div>
      </div>
      <div class="summary-item">
        <div class="summary-label">运行状态</div>
        <div class="summary-value">
          <StatusBadge :status="data.mode ?? 'FORMAL'" />
        </div>
      </div>
    </div>

    <div v-if="data" class="grid">
      <ValueCard v-for="card in data.items" :key="card.itemId" :card="card" />
    </div>
    <div v-if="data && !hasPublishedValue && acquisition?.state !== 'RUNNING'" class="empty-state">
      当前尚无已发布监测数据。汇率可重新获取，材料数据可前往“来源与录入”手动提交。
    </div>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}
.acquisition-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border, #d8dee8);
  border-radius: 12px;
  background: var(--surface, #fff);
}

.acquisition-banner strong,
.acquisition-banner span { display: block; }
.acquisition-banner span { margin-top: 4px; color: var(--muted, #64748b); font-size: 13px; }
.acquisition-banner.is-running { border-color: #93c5fd; background: #eff6ff; }
.acquisition-banner.is-failed { border-color: #fca5a5; background: #fef2f2; }
.acquisition-banner.is-success { border-color: #86efac; background: #f0fdf4; }
.action-btn { flex: 0 0 auto; border: 0; border-radius: 9px; padding: 9px 13px; color: #fff; background: #2563eb; cursor: pointer; }
.action-btn:disabled { cursor: wait; opacity: 0.65; }
@media (max-width: 720px) { .acquisition-banner { align-items: flex-start; flex-direction: column; } }</style>
