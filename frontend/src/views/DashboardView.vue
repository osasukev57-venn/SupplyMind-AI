<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchOverview } from '../api/dashboard'
import type { OverviewResponse } from '../types/dashboard'
import ValueCard from '../components/ValueCard.vue'

const data = ref<OverviewResponse | null>(null)
const error = ref<string | null>(null)

onMounted(async () => {
  data.value = await fetchOverview()
  if (data.value === null) {
    error.value = '总览数据不可用（后端离线或返回错误）'
  }
})
</script>

<template>
  <div>
    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="data?.mode === 'DEMO'" class="demo-banner">
      演示模式（DEMO）— 当前展示的为演示数据，非正式业务数据
    </div>
    <div v-if="data === null && !error" class="muted">加载中…</div>
    <div v-if="data" class="grid">
      <ValueCard v-for="card in data.items" :key="card.itemId" :card="card" />
    </div>
    <div v-if="data && data.items.length === 0" class="panel muted">
      当前没有启用的监测标的
    </div>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}
</style>
