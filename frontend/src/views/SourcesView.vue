<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchSources } from '../api/dashboard'
import type { SourcesResponse } from '../types/dashboard'
import StatusBadge from '../components/StatusBadge.vue'

const data = ref<SourcesResponse | null>(null)
const error = ref<string | null>(null)

onMounted(async () => {
  data.value = await fetchSources()
  if (data.value === null) {
    error.value = '来源数据不可用'
  }
})
</script>

<template>
  <div>
    <div v-if="error" class="error-banner">{{ error }}</div>

    <div v-if="data" class="panel">
      <h2>来源列表与三层路线（模式：{{ data.mode ?? '—' }}）</h2>
      <table class="sm-table">
        <thead>
          <tr>
            <th>标的</th>
            <th>启用</th>
            <th>来源意图</th>
            <th>Provider</th>
            <th>实际来源</th>
            <th>访问方式</th>
            <th>路线</th>
            <th>降级原因</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in data.items" :key="item.itemId">
            <td>{{ item.displayName }}（{{ item.itemId }}）</td>
            <td>{{ item.enabled ? '是' : '否' }}</td>
            <td>{{ item.sourceIntent ?? '—' }}</td>
            <td>{{ item.providerType ?? '—' }}</td>
            <td>{{ item.actualSourceName ?? '—' }}</td>
            <td>{{ item.accessMethod ?? '—' }}</td>
            <td>{{ item.routeDecision ?? '—' }}</td>
            <td>{{ item.fallbackReason ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="data" class="panel">
      <h2>手动录入 / 文件导入</h2>
      <div class="entry-card">
        <StatusBadge :status="data.manualEntry.status" />
        <span class="entry-label">手动录入</span>
        <span class="muted">{{ data.manualEntry.message }}</span>
      </div>
      <div class="entry-card">
        <StatusBadge :status="data.importEntry.status" />
        <span class="entry-label">文件导入</span>
        <span class="muted">{{ data.importEntry.message }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.entry-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--sm-border);
}
.entry-label {
  font-weight: 600;
}
</style>
