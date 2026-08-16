<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchSources, submitImport, submitManual, submitSyntheticDemo } from '../api/dashboard'
import type { SourcesResponse } from '../types/dashboard'
import StatusBadge from '../components/StatusBadge.vue'

const data = ref<SourcesResponse | null>(null)
const error = ref<string | null>(null)

// Manual entry: the form goes through the REAL ManualMaterialIntakeService boundary - the
// backend creates the raw + RECEIVED/PARSED+PENDING lifecycle timeline and returns the real
// runId/rawRef/timelineRef evidence.
const manualItemId = ref('')
const manualSource = ref('')
const manualBusinessDate = ref('')
const manualValue = ref('')
const manualUnit = ref('')
const manualStatus = ref<string | null>(null)
const manualMessage = ref<string | null>(null)
const manualEvidence = ref<{ runId: string; rawRef: string; timelineRef: string } | null>(null)

// File import: the raw file is uploaded to the backend LocalImport boundary (CSV and XLSX are
// really parsed); accepted rows are RECEIVED+PENDING evidence with real refs.
const importStatus = ref<string | null>(null)
const importMessage = ref<string | null>(null)
const importAccepted = ref<{ rowNumber: number; runId: string; rawRef: string; timelineRef: string; processingStage: string | null; validationStatus: string | null }[]>([])
const importErrors = ref<{ rowNumber: number; message: string }[]>([])

// Synthetic demo entry: runs the real deterministic SyntheticDemoDataProvider.
const demoStatus = ref<string | null>(null)
const demoMessage = ref<string | null>(null)

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
  manualEvidence.value = {
    runId: response.runId,
    rawRef: response.rawRef,
    timelineRef: response.timelineRef
  }
}

async function onImportFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files && input.files.length > 0 ? input.files[0] : null
  importStatus.value = null
  importMessage.value = null
  importAccepted.value = []
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
  importAccepted.value = response.acceptedRows
  importErrors.value = response.rowErrors
}

async function runSyntheticDemo(): Promise<void> {
  const response = await submitSyntheticDemo()
  if (response === null) {
    demoStatus.value = 'REJECTED'
    demoMessage.value = '演示数据生成失败：后端不可用'
    return
  }
  demoStatus.value = response.status
  demoMessage.value = response.message
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
      <div v-if="manualEvidence" class="entry-note">
        受理证据：runId={{ manualEvidence.runId }}；rawRef={{ manualEvidence.rawRef }}；
        timelineRef={{ manualEvidence.timelineRef }}
      </div>
    </div>

    <div class="panel">
      <h2>文件导入</h2>
      <div class="entry-card">
        <StatusBadge :status="importStatus ?? '—'" />
        <span class="entry-label">状态：{{ importStatus ?? '未提交' }}</span>
        <a class="template-link" href="/api/dashboard/import/template" download>
          下载导入模板（CSV）
        </a>
      </div>
      <input type="file" accept=".csv,.xlsx" @change="onImportFile" />
      <div v-if="importMessage" class="entry-note">{{ importMessage }}</div>
      <div v-if="importErrors.length > 0" class="entry-errors">
        <div v-for="err in importErrors" :key="err.rowNumber" class="error-line">
          第 {{ err.rowNumber }} 行：{{ err.message }}
        </div>
      </div>
      <table v-if="importAccepted.length > 0" class="sm-table">
        <thead>
          <tr>
            <th>行号</th>
            <th>runId</th>
            <th>rawRef</th>
            <th>timelineRef</th>
            <th>阶段</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in importAccepted.slice(0, 20)" :key="row.rowNumber">
            <td>{{ row.rowNumber }}</td>
            <td>{{ row.runId }}</td>
            <td>{{ row.rawRef }}</td>
            <td>{{ row.timelineRef }}</td>
            <td>{{ row.processingStage ?? '—' }}</td>
            <td>{{ row.validationStatus ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="panel">
      <h2>Synthetic 演示数据</h2>
      <div v-if="demoStatus" class="entry-card">
        <StatusBadge :status="demoStatus" />
        <span class="entry-label">{{ demoStatus }}</span>
      </div>
      <button @click="runSyntheticDemo">生成演示数据（SyntheticDemo）</button>
      <div v-if="demoMessage" class="entry-note">{{ demoMessage }}</div>
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
.template-link {
  font-size: 12px;
  color: var(--sm-accent);
  margin-left: auto;
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
