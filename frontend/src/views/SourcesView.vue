<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchSources } from '../api/dashboard'
import type { SourcesResponse } from '../types/dashboard'
import StatusBadge from '../components/StatusBadge.vue'

const data = ref<SourcesResponse | null>(null)
const error = ref<string | null>(null)

// Manual entry form (Day8 write boundary: submit only reports PENDING - nothing is persisted).
const manualItemId = ref('')
const manualSource = ref('')
const manualBusinessDate = ref('')
const manualValue = ref('')
const manualUnit = ref('')
const manualSubmitted = ref(false)
const manualMessage = ref('')

// File import entry (Day8 write boundary: preview only, submit reports PENDING).
const importFileName = ref('')
const importPreview = ref<{ rowNumber: number; cells: string[] }[]>([])
const importErrors = ref<{ rowNumber: number; message: string }[]>([])
const importSubmitted = ref(false)

function submitManual(): void {
  if (!manualItemId.value || !manualValue.value || !manualBusinessDate.value) {
    manualMessage.value = '请填写标的、业务日期和值'
    manualSubmitted.value = true
    return
  }
  manualSubmitted.value = true
  manualMessage.value =
    '已受理（PENDING）— 手动录入的正式写入属 Day8 边界，当前仅记录受理状态，未写库'
}

function previewFile(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files && input.files.length > 0 ? input.files[0] : null
  importErrors.value = []
  importPreview.value = []
  if (!file) {
    importFileName.value = ''
    return
  }
  importFileName.value = file.name
  file.text().then((text) => {
    const lines = text.split(/\r?\n/)
    lines.forEach((line, index) => {
      if (index === 0 || line.trim() === '') return
      const cells = line.split(',')
      const message = validateRow(cells)
      if (message) {
        importErrors.value.push({ rowNumber: index + 1, message })
      } else {
        importPreview.value.push({ rowNumber: index + 1, cells })
      }
    })
  })
}

/** Form-level validation only (missing/blank columns) - no business-value computation. */
function validateRow(cells: string[]): string | null {
  if (cells.length < 3) return '列数不足（期望至少 3 列：标的, 业务日期, 值）'
  if (!cells[0] || cells[0].trim() === '') return '标的为空'
  if (!cells[1] || cells[1].trim() === '') return '业务日期为空'
  if (!cells[2] || cells[2].trim() === '') return '值为空'
  return null
}

function submitImport(): void {
  importSubmitted.value = true
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
      <div class="entry-card">
        <StatusBadge :status="manualSubmitted ? 'PENDING' : '—'" />
        <span class="entry-label">状态：{{ manualSubmitted ? 'PENDING' : '未提交' }}</span>
      </div>
      <form class="manual-form" @submit.prevent="submitManual">
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
      <div v-if="manualSubmitted" class="entry-note">{{ manualMessage }}</div>
    </div>

    <div class="panel">
      <h2>文件导入</h2>
      <div class="entry-card">
        <StatusBadge :status="importSubmitted ? 'PENDING' : '—'" />
        <span class="entry-label">状态：{{ importSubmitted ? 'PENDING' : '未提交' }}</span>
      </div>
      <input type="file" accept=".csv,.xlsx" @change="previewFile" />
      <div v-if="importFileName" class="entry-note">
        文件：{{ importFileName }}（预览 {{ importPreview.length }} 行，错误 {{ importErrors.length }} 行）
      </div>
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
      <button :disabled="importErrors.length > 0 || importPreview.length === 0"
        @click="submitImport">
        提交（PENDING）
      </button>
      <div v-if="importSubmitted" class="entry-note">
        已受理（PENDING）— 文件导入的正式写入属 Day8 边界，当前仅记录受理状态，未写库
      </div>
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
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
