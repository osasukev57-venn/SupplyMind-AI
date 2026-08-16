<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchSources, submitImport, submitManual } from '../api/dashboard'
import type { SourcesResponse } from '../types/dashboard'
import StatusBadge from '../components/StatusBadge.vue'

const data = ref<SourcesResponse | null>(null)
const error = ref<string | null>(null)

// Manual entry: the form is sent to the BACKEND accept-into-PENDING endpoint; the displayed
// status/message are the backend's structured response (nothing is persisted - Day8 boundary).
const manualItemId = ref('')
const manualSource = ref('')
const manualBusinessDate = ref('')
const manualValue = ref('')
const manualUnit = ref('')
const manualStatus = ref<string | null>(null)
const manualMessage = ref<string | null>(null)

// File import: the raw file (CSV or XLSX) is uploaded to the BACKEND; the preview rows and
// per-row errors are the backend's real parse result. XLSX is explicitly REJECTED by the
// backend - the frontend never fakes a parse (no file.text()).
const importStatus = ref<string | null>(null)
const importMessage = ref<string | null>(null)
const importPreview = ref<{ rowNumber: number; cells: string[] }[]>([])
const importErrors = ref<{ rowNumber: number; message: string }[]>([])

async function submitManualForm(): Promise<void> {
  const response = await submitManual({
    itemId: manualItemId.value,
    source: manualSource.value,
    businessDate: manualBusinessDate.value,
    value: manualValue.value,
    unit: manualUnit.value
  })
  if (response === null) {
    manualStatus.value = 'REJECTED'
    manualMessage.value = '提交失败：后端不可用或参数被拒绝'
    return
  }
  manualStatus.value = response.status
  manualMessage.value = response.message
}

async function onImportFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files && input.files.length > 0 ? input.files[0] : null
  importStatus.value = null
  importMessage.value = null
  importPreview.value = []
  importErrors.value = []
  if (!file) return
  const response = await submitImport(file)
  if (response === null) {
    importStatus.value = 'REJECTED'
    importMessage.value = '上传失败：后端不可用'
    return
  }
  importStatus.value = response.status
  importMessage.value = response.message
  importPreview.value = response.previewRows
  importErrors.value = response.rowErrors
}

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

    <div class="panel">
      <h2>手动录入</h2>
      <div v-if="manualStatus" class="entry-card">
        <StatusBadge :status="manualStatus" />
        <span class="entry-label">{{ manualStatus }}</span>
      </div>
      <form class="manual-form" @submit.prevent="submitManualForm">
        <label>标的 itemId
          <input v-model="manualItemId" placeholder="如 FX.USD.CNY.PBOC_MID" />
        </label>
        <label>来源（actualSourceName）
          <input v-model="manualSource" placeholder="实际来源名称（不得冒充）" />
        </label>
        <label>业务日期
          <input v-model="manualBusinessDate" type="date" />
        </label>
        <label>值（原字符串）
          <input v-model="manualValue" placeholder="如 6.7904" />
        </label>
        <label>单位
          <input v-model="manualUnit" placeholder="如 CNY/1 USD" />
        </label>
        <button type="submit">提交（PENDING）</button>
      </form>
      <div v-if="manualMessage" class="entry-note">{{ manualMessage }}</div>
    </div>

    <div class="panel">
      <h2>文件导入</h2>
      <div v-if="importStatus" class="entry-card">
        <StatusBadge :status="importStatus" />
        <span class="entry-label">{{ importStatus }}</span>
      </div>
      <input type="file" accept=".csv,.xlsx" @change="onImportFile" />
      <div v-if="importMessage" class="entry-note">{{ importMessage }}</div>
      <div v-if="importErrors.length > 0" class="entry-errors">
        <div v-for="err in importErrors" :key="err.rowNumber" class="error-line">
          第 {{ err.rowNumber }} 行：{{ err.message }}
        </div>
      </div>
      <table v-if="importPreview.length > 0" class="sm-table">
        <thead>
          <tr>
            <th>行号</th>
            <th>标的</th>
            <th>业务日期</th>
            <th>值</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in importPreview.slice(0, 20)" :key="row.rowNumber">
            <td>{{ row.rowNumber }}</td>
            <td>{{ row.cells[0] }}</td>
            <td>{{ row.cells[1] }}</td>
            <td>{{ row.cells[2] }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.entry-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px dashed var(--sm-border);
}
.entry-label {
  font-weight: 600;
}
.entry-note {
  margin-top: 8px;
  font-size: 12px;
  color: var(--sm-muted);
}
.entry-errors {
  margin-top: 8px;
}
.error-line {
  font-size: 12px;
  color: var(--sm-bad);
}
.manual-form {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 10px;
  margin-top: 10px;
}
.manual-form label {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  gap: 4px;
}
.manual-form input {
  padding: 6px 8px;
  border: 1px solid var(--sm-border);
  border-radius: 4px;
}
button {
  padding: 6px 14px;
  border: 1px solid var(--sm-accent);
  background: var(--sm-accent);
  color: #fff;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  align-self: end;
}
</style>
