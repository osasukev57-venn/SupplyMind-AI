<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchOverview } from '../api/dashboard'
import type { OverviewResponse } from '../types/dashboard'
import ValueCard from '../components/ValueCard.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { modeLabel } from '../lib/labels'

const data = ref<OverviewResponse | null>(null)
const error = ref<string | null>(null)

const activeCount = computed(() => data.value?.items.length ?? 0)

onMounted(async () => {
  data.value = await fetchOverview()
  if (data.value === null) {
    error.value = '总览数据暂时不可用，请稍后重试'
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
    <div v-if="data?.mode === 'DEMO'" class="demo-banner">
      当前为演示数据，仅用于功能体验，不用于正式业务判断
    </div>
    <div v-if="data === null && !error" class="empty-state">加载中…</div>

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
    <div v-if="data && data.items.length === 0" class="empty-state">
      当前没有启用的监测项，可前往“动态配置”新增
    </div>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}
</style>
