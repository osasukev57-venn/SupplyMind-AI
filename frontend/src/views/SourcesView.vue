<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchSources, submitImport, submitManual, submitSyntheticDemo } from '../api/dashboard'
import type { SourcesResponse } from '../types/dashboard'
import StatusBadge from '../components/StatusBadge.vue'
import { accessLabel, fallbackReasonLabel, modeLabel, providerLabel, routeLabel, stageLabel, validationLabel } from '../lib/labels'

const data = ref<SourcesResponse | null>(null)
const error = ref<string | null>(null)

// Manual entry: the form goes through the real backend intake boundary - the server creates
// the evidence and returns the intake reference; the page only displays what came back.
const manualItemId = ref('')
const manualSource = ref('')
const manualBusinessDate = ref('')
const manualValue = ref('')
const manualUnit = ref('')
const manualStatus = ref<string | null>(null)
const manualMessage = ref<string | null>(null)
const manualEvidence = ref<{ runId: string; rawRef: string; timelineRef: string } | null>(null)
const showManualDetail = ref(false)

// File import: the file is uploaded to the backend, which really parses it; accepted rows are
// intake evidence with real references, invalid rows are reported per row.
const importStatus = ref<string | null>(null)
const importMessage = ref<string | null>(null)
const importAccepted = ref<{ rowNumber: number; runId: string; rawRef: string; timelineRef: string; processingStage: string | null; validationStatus: string | null }[]>([])
const importErrors = ref<{ rowNumber: number; message: string }[]>([])
const showImportDetail = ref(false)

// Synthetic demo entry: deterministic demo data for the demo scenario only.
const demoStatus = ref<string | null>(null)
const demoMessage = ref<string | null>(null)

async function submitManualForm(): Promise<void> {
  if (!manualItemId.value || !manualSource.value || !manualBusinessDate.value || !manualValue.value || !manualUnit.value) {
    manualStatus.value = 'REJECTED'
    manualMessage.value = '请填写监测项编号、来源、业务日期、值和单位'
    return
  }
  const response = await submitManual({
    itemId: manualItemId.value,
    source: manualSource.value,
    businessDate: manualBusinessDate.value,
    value: manualValue.value,
    unit: manualUnit.value
  })
  if (response === null) {
    manualStatus.value = 'REJECTED'
    manualMessage.value = '提交失败：服务暂时不可用，请稍后重试'
    return
  }
  manualStatus.value = response.status
  manualMessage.value = response.message
  manualEvidence.value = {
    runId: response.runId,
    rawRef: response.rawRef,
    timelineRef: response.timelineRef
  }
  showManualDetail.value = true
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
    importMessage.value = '上传失败：服务暂时不可用，请稍后重试'
    return
  }
  importStatus.value = response.status
  importMessage.value = response.message
  importAccepted.value = response.acceptedRows
  importErrors.value = response.rowErrors
  if (response.acceptedRows.length > 0) showImportDetail.value = true
}

async function runSyntheticDemo(): Promise<void> {
  const response = await submitSyntheticDemo()
  if (response === null) {
    demoStatus.value = 'REJECTED'
    demoMessage.value = '演示数据生成失败：服务暂时不可用，请稍后重试'
    return
  }
  demoStatus.value = response.status
  demoMessage.value = response.message
}

onMounted(async () => {
  data.value = await fetchSources()
  if (data.value === null) {
    error.value = '来源数据暂时不可用，请稍后重试'
  }
})
</script>

<template>
  <div>
    <header class="page-head">
      <div class="page-eyebrow">数据管理</div>
      <h1 class="page-title">来源与录入</h1>
      <p class="page-desc">查看各监测项的采集来源与路线，以及人工录入、文件导入和演示数据入口。所有录入均由系统校验后受理。</p>
    </header>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <section class="section">
      <div class="section-head">
        <h2>来源列表</h2>
        <StatusBadge v-if="data?.mode" :status="data.mode ?? 'FORMAL'" />
      </div>
      <div class="section-body flat">
        <div class="table-scroll">
          <table class="sm-table">
            <thead>
              <tr>
                <th>监测项</th>
                <th>状态</th>
                <th>来源意图</th>
                <th>接入方式</th>
                <th>数据来源</th>
                <th>采集路线</th>
                <th>降级原因</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in data?.items" :key="item.itemId">
                <td>
                  <div>{{ item.displayName }}</div>
                  <div class="id-cell muted">{{ item.itemId }}</div>
                </td>
                <td><StatusBadge :status="item.enabled ? 'ENABLED' : 'DISABLED'" /></td>
                <td>{{ item.sourceIntent ?? '—' }}</td>
                <td>{{ providerLabel(item.providerType) }}</td>
                <td>{{ item.actualSourceName ?? '—' }}</td>
                <td>
                  {{ routeLabel(item.routeDecision) }}
                  <span v-if="item.fallbackReason" class="muted">（{{ fallbackReasonLabel(item.fallbackReason) }}）</span>
                </td>
                <td>{{ item.fallbackReason ? fallbackReasonLabel(item.fallbackReason) : '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="data && data.items.length === 0" class="empty-state">暂无来源信息</div>
      </div>
    </section>

    <section class="section">
      <div class="section-head">
        <h2>人工录入</h2>
        <span class="muted">录入数据需人工核对来源，受理后进入待处理状态</span>
      </div>
      <div class="section-body">
        <div v-if="manualStatus" class="entry-card">
          <StatusBadge :status="manualStatus" />
          <span class="entry-label">{{ manualStatus }}</span>
        </div>
        <form class="form-grid" @submit.prevent="submitManualForm">
          <label>监测项编号
            <input v-model="manualItemId" placeholder="如 FX.USD.CNY.PBOC_MID" />
          </label>
          <label>数据来源
            <input v-model="manualSource" placeholder="实际来源名称，不得冒充" />
          </label>
          <label>业务日期
            <input v-model="manualBusinessDate" type="date" />
          </label>
          <label>数值
            <input v-model="manualValue" placeholder="如 6.7904" />
          </label>
          <label>单位
            <input v-model="manualUnit" placeholder="如 CNY/1 USD" />
          </label>
        </form>
        <div class="form-actions">
          <button class="btn-primary" type="submit" @click="submitManualForm">提交录入</button>
        </div>
        <div v-if="manualMessage" class="entry-note">{{ manualMessage }}</div>
        <div v-if="manualEvidence && showManualDetail" class="entry-note">
          <button class="btn-ghost" @click="showManualDetail = !showManualDetail">
            {{ showManualDetail ? '收起受理详情' : '查看受理详情' }}
          </button>
          <div v-if="showManualDetail" class="detail-box">
            <div>受理编号：{{ manualEvidence.runId }}</div>
            <div>原始记录：{{ manualEvidence.rawRef }}</div>
            <div>生命周期：{{ manualEvidence.timelineRef }}</div>
          </div>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="section-head">
        <h2>文件导入</h2>
        <a class="template-link" href="/api/dashboard/import/template" download>
          下载导入模板
        </a>
      </div>
      <div class="section-body">
        <div class="entry-card">
          <StatusBadge :status="importStatus ?? '—'" />
          <span class="entry-label">状态：{{ importStatus ?? '未提交' }}</span>
        </div>
        <input type="file" accept=".csv,.xlsx" @change="onImportFile" />
        <div v-if="importMessage" class="entry-note">{{ importMessage }}</div>
        <div v-if="importErrors.length > 0" class="entry-errors">
          <div v-for="err in importErrors" :key="err.rowNumber" class="error-line">
            第 {{ err.rowNumber }} 行：{{ err.message }}
          </div>
        </div>
        <div v-if="importAccepted.length > 0" class="table-scroll" style="margin-top: 10px">
          <table class="sm-table">
            <thead>
              <tr>
                <th>行号</th>
                <th>阶段</th>
                <th>状态</th>
                <th>受理详情</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in importAccepted.slice(0, 20)" :key="row.rowNumber">
                <td class="num">{{ row.rowNumber }}</td>
                <td>{{ stageLabel(row.processingStage) }}</td>
                <td><StatusBadge :status="row.validationStatus ?? 'PENDING'" /></td>
                <td>
                  <button class="btn-ghost" @click="showImportDetail = !showImportDetail">
                    {{ showImportDetail ? '收起' : '详情' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="showImportDetail" class="detail-box">
            <div v-for="row in importAccepted.slice(0, 20)" :key="row.rowNumber" class="detail-line">
              第 {{ row.rowNumber }} 行：受理 {{ row.runId }}；原始 {{ row.rawRef }}；生命周期 {{ row.timelineRef }}
            </div>
          </div>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="section-head">
        <h2>演示数据</h2>
        <span class="muted">仅用于功能演示，不进入正式业务判断</span>
      </div>
      <div class="section-body">
        <div v-if="demoStatus" class="entry-card">
          <StatusBadge :status="demoStatus" />
          <span class="entry-label">{{ demoStatus }}</span>
        </div>
        <button class="btn-demo" @click="runSyntheticDemo">生成演示数据</button>
        <div v-if="demoMessage" class="entry-note">{{ demoMessage }}</div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.entry-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px dashed var(--sm-border);
  margin-bottom: 10px;
}
.entry-label {
  font-weight: 600;
}
.entry-note {
  margin-top: 10px;
  font-size: 12px;
  color: var(--sm-muted);
}
.entry-errors {
  margin-top: 10px;
}
.error-line {
  font-size: 12px;
  color: var(--sm-bad);
}
.detail-box {
  margin-top: 8px;
  padding: 10px 12px;
  background: var(--sm-surface-2);
  border: 1px solid var(--sm-border);
  border-radius: var(--sm-radius);
  font-family: var(--sm-font-mono);
  font-size: 11.5px;
  color: var(--sm-muted);
  word-break: break-all;
}
.detail-line {
  padding: 2px 0;
}
.template-link {
  font-size: 12.5px;
  color: var(--sm-accent);
  text-decoration: none;
}
.template-link:hover {
  text-decoration: underline;
}
</style>
