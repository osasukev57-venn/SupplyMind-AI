<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  addItem,
  createBackfillJob,
  fetchBackfillJobs,
  fetchCapabilities,
  fetchConfigHistory,
  fetchConfigItems,
  replaceItem,
  retryBackfillJob,
  runBackfillJob,
  setEnabled
} from '../api/config'
import type {
  AddItemRequest,
  BackfillJobView,
  CapabilityView,
  ConfigView,
  HistoryEntry
} from '../types/config'
import StatusBadge from '../components/StatusBadge.vue'

/**
 * D8-T01 dynamic configuration page (H07/H08/H09). The page drives the backend workflow with
 * controlled request DTOs: the backend generates configVersion/routeEffectiveAt/jobId/audit
 * time and validates provider capability. The page never computes business values.
 */

const config = ref<ConfigView | null>(null)
const history = ref<HistoryEntry[]>([])
const capabilities = ref<CapabilityView[]>([])
const jobs = ref<BackfillJobView[]>([])
const errorMessage = ref<string | null>(null)
const notice = ref<string | null>(null)

// ADD form
const newItemId = ref('')
const newDisplayName = ref('')
const newSourceIntent = ref('PBOC')
const newProviderType = ref('official_web')
const newRateKind = ref('人民币汇率中间价')
const newUnit = ref('')
const newBaseCurrency = ref('')
const newBackfillFrom = ref('')
const newBackfillTo = ref('')

// REPLACE form
const oldItemId = ref('')
const replItemId = ref('')
const replDisplayName = ref('')
const replSourceIntent = ref('SMM')

// backfill form
const jobItemId = ref('')
const jobFrom = ref('')
const jobTo = ref('')

const loading = ref(true)

function applyResult(result: { config: ConfigView } | ConfigView | null) {
  if (!result) {
    return
  }
  const view = 'config' in result ? result.config : result
  config.value = view
  void loadHistory()
  void loadJobs()
}

async function refresh() {
  loading.value = true
  errorMessage.value = null
  notice.value = null
  await loadConfig()
  await loadHistory()
  await loadCapabilities()
  await loadJobs()
  loading.value = false
}

async function loadConfig() {
  config.value = await fetchConfigItems()
}

async function loadHistory() {
  history.value = (await fetchConfigHistory()) ?? []
}

async function loadCapabilities() {
  const response = await fetchCapabilities()
  capabilities.value = response?.providers ?? []
}

async function loadJobs() {
  const response = await fetchBackfillJobs()
  jobs.value = response?.jobs ?? []
}

function controlledAddRequest(): AddItemRequest | null {
  if (!newItemId.value || !newDisplayName.value || !newUnit.value || !newBaseCurrency.value) {
    errorMessage.value = '新增表单必填：itemId、显示名、单位、基础币种'
    return null
  }
  const material = newRateKind.value === 'material'
  return {
    itemId: newItemId.value,
    displayName: newDisplayName.value,
    sourceIntent: newSourceIntent.value,
    providerType: newProviderType.value,
    accessMethod: material ? 'manual' : 'public_official_html',
    actualSourceName: material ? '人工录入（Manual）' : '中国人民银行官网（授权中国外汇交易中心公布）',
    routeDecision: material ? 'fallback_manual' : 'primary',
    fallbackReason: material ? 'MANUAL_FALLBACK' : null,
    externalCode: newItemId.value.split('.')[1] ?? newItemId.value,
    sourceFieldKey: material ? 'material-field-key' : `1${newBaseCurrency.value}对人民币`,
    rateKind: newRateKind.value,
    calculationVersion: 'arithmetic-mean-v1',
    calculationScale: material ? 2 : 8,
    displayScale: material ? 2 : 4,
    roundingMode: 'HALF_UP',
    calendarVersion: 'weekday-asia-shanghai-v1',
    currency: 'CNY',
    baseCurrency: newBaseCurrency.value,
    unit: newUnit.value,
    materialValidation: material
      ? {
          valueMinExclusive: '0',
          valueMaxInclusive: null,
          staleThresholdDays: 7,
          canonicalSpecCode: newItemId.value.split('.')[1] ?? 'ADC12',
          acceptedSpecAliases: []
        }
      : null,
    backfillFrom: newBackfillFrom.value || null,
    backfillTo: newBackfillTo.value || null
  }
}

async function submitAdd() {
  errorMessage.value = null
  notice.value = null
  const request = controlledAddRequest()
  if (!request) {
    return
  }
  const result = await addItem(request)
  if (!result) {
    errorMessage.value = '新增失败：后端不可用或参数被拒绝（服务端 capability 校验为准）'
    return
  }
  notice.value = `已激活 configVersion=${result.config.configVersion}；回填任务 ${result.backfillJobs.length} 个`
  applyResult(result)
  newItemId.value = ''
  newDisplayName.value = ''
  newUnit.value = ''
  newBaseCurrency.value = ''
  newBackfillFrom.value = ''
  newBackfillTo.value = ''
}

async function toggleEnabled(item: { itemId: string; enabled: boolean }) {
  errorMessage.value = null
  notice.value = null
  const result = await setEnabled(item.itemId, !item.enabled)
  if (!result) {
    errorMessage.value = '操作失败：后端不可用或参数被拒绝'
    return
  }
  notice.value = `${item.enabled ? '停用' : '启用'} ${item.itemId} 成功（configVersion=${result.configVersion}）`
  applyResult(result)
}

async function submitReplace() {
  errorMessage.value = null
  notice.value = null
  if (!oldItemId.value || !replItemId.value || !replDisplayName.value) {
    errorMessage.value = '替换表单必填：旧 itemId、新 itemId、显示名'
    return
  }
  const base: AddItemRequest = {
    itemId: replItemId.value,
    displayName: replDisplayName.value,
    sourceIntent: replSourceIntent.value,
    providerType: 'manual',
    accessMethod: 'manual',
    actualSourceName: '人工录入（Manual）',
    routeDecision: 'fallback_manual',
    fallbackReason: 'MANUAL_FALLBACK',
    externalCode: replItemId.value.split('.')[1] ?? 'AZ91D',
    sourceFieldKey: 'material-field-key',
    rateKind: 'material',
    calculationVersion: 'arithmetic-mean-v1',
    calculationScale: 2,
    displayScale: 2,
    roundingMode: 'HALF_UP',
    calendarVersion: 'weekday-asia-shanghai-v1',
    currency: 'CNY',
    baseCurrency: 'CNY',
    unit: '元/吨',
    materialValidation: {
      valueMinExclusive: '0',
      valueMaxInclusive: null,
      staleThresholdDays: 7,
      canonicalSpecCode: replItemId.value.split('.')[1] ?? 'AZ91D',
      acceptedSpecAliases: []
    },
    backfillFrom: null,
    backfillTo: null
  }
  const result = await replaceItem({ oldItemId: oldItemId.value, newItem: base })
  if (!result) {
    errorMessage.value = '替换失败：后端不可用或参数被拒绝'
    return
  }
  notice.value = `已替换 ${oldItemId.value} -> ${replItemId.value}（configVersion=${result.config.configVersion}）`
  applyResult(result)
  oldItemId.value = ''
  replItemId.value = ''
  replDisplayName.value = ''
}

async function submitCreateJob() {
  errorMessage.value = null
  notice.value = null
  if (!jobItemId.value || !jobFrom.value || !jobTo.value) {
    errorMessage.value = '回填任务必填：itemId、from、to'
    return
  }
  const job = await createBackfillJob(jobItemId.value, jobFrom.value, jobTo.value)
  if (!job) {
    errorMessage.value = '创建回填任务失败：后端不可用或参数被拒绝'
    return
  }
  notice.value = `任务 ${job.jobId} 状态=${job.status}`
  await loadJobs()
}

async function runJob(jobId: string) {
  errorMessage.value = null
  notice.value = null
  const job = await runBackfillJob(jobId)
  if (!job) {
    errorMessage.value = '运行任务失败：后端不可用'
    return
  }
  notice.value = `任务 ${job.jobId} 状态=${job.status}${job.failureReasons.length ? '（失败原因：' + job.failureReasons.join('；') + '）' : ''}`
  await loadJobs()
}

async function retryJob(jobId: string) {
  errorMessage.value = null
  notice.value = null
  const job = await retryBackfillJob(jobId)
  if (!job) {
    errorMessage.value = '重试任务失败：后端不可用'
    return
  }
  notice.value = `任务 ${job.jobId} 重试后状态=${job.status}`
  await loadJobs()
}

onMounted(refresh)
</script>

<template>
  <div>
    <h1>动态配置</h1>
    <p class="muted">
      D8-T01：新增/停用/替换监测标的均不改代码、不重启；配置版本由服务端原子激活，面板随配置重构。
    </p>

    <div v-if="errorMessage" class="error-banner">{{ errorMessage }}</div>
    <div v-if="notice" class="demo-banner">{{ notice }}</div>

    <section class="panel">
      <h2>当前监测项（configVersion={{ config?.configVersion ?? '-' }}，mode={{ config?.mode ?? '-' }}）</h2>
      <table v-if="config && config.items.length > 0" class="sm-table">
        <thead>
          <tr>
            <th>itemId</th>
            <th>名称</th>
            <th>状态</th>
            <th>来源意图</th>
            <th>Provider/路线</th>
            <th>实际来源</th>
            <th>单位</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in config.items" :key="item.itemId">
            <td>{{ item.itemId }}</td>
            <td>{{ item.displayName }}</td>
            <td><StatusBadge :status="item.enabled ? 'ENABLED' : 'DISABLED'" /></td>
            <td>{{ item.sourceIntent }}</td>
            <td>{{ item.providerType }} / {{ item.routeDecision }}</td>
            <td>{{ item.actualSourceName }}</td>
            <td>{{ item.unit }}</td>
            <td>
              <button v-if="item.enabled" @click="toggleEnabled(item)">停用</button>
              <button v-else @click="toggleEnabled(item)">启用</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">无监测项或后端不可用</p>
    </section>

    <section class="panel">
      <h2>新增监测标的（H07/H08）</h2>
      <div class="form-grid">
        <label>itemId <input v-model="newItemId" placeholder="如 FX.GBP.CNY.PBOC_MID" /></label>
        <label>显示名 <input v-model="newDisplayName" placeholder="如 英镑/人民币中间价" /></label>
        <label>来源意图 <input v-model="newSourceIntent" placeholder="PBOC" /></label>
        <label>Provider 类型
          <select v-model="newProviderType">
            <option value="official_web">official_web</option>
            <option value="manual">manual</option>
            <option value="local_import">local_import</option>
          </select>
        </label>
        <label>rateKind <input v-model="newRateKind" placeholder="人民币汇率中间价 或 material" /></label>
        <label>单位 <input v-model="newUnit" placeholder="如 CNY/1 GBP 或 元/吨" /></label>
        <label>基础币种 <input v-model="newBaseCurrency" placeholder="如 GBP 或 CNY" /></label>
        <label>回填起点 <input v-model="newBackfillFrom" type="date" /></label>
        <label>回填终点 <input v-model="newBackfillTo" type="date" /></label>
      </div>
      <button @click="submitAdd">新增并激活（服务端校验 capability）</button>
      <p class="muted">configVersion / routeEffectiveAt / 审计时间由服务端生成，页面不提交这些字段。</p>
    </section>

    <section class="panel">
      <h2>替换监测标的（H09，旧标的停用且历史保留）</h2>
      <div class="form-grid">
        <label>旧 itemId <input v-model="oldItemId" placeholder="如 MAT.AZ91D.SMM" /></label>
        <label>新 itemId <input v-model="replItemId" placeholder="如 MAT.REPL-01.SMM" /></label>
        <label>新显示名 <input v-model="replDisplayName" placeholder="如 AZ91D替代材料（SMM意图）" /></label>
        <label>来源意图 <input v-model="replSourceIntent" placeholder="SMM 或 Asian Metal" /></label>
      </div>
      <button @click="submitReplace">替换（旧项停用，历史不删除）</button>
    </section>

    <section class="panel">
      <h2>回填任务</h2>
      <div class="form-grid">
        <label>itemId <input v-model="jobItemId" placeholder="如 FX.GBP.CNY.PBOC_MID" /></label>
        <label>from <input v-model="jobFrom" type="date" /></label>
        <label>to <input v-model="jobTo" type="date" /></label>
      </div>
      <button @click="submitCreateJob">创建回填任务</button>
      <table v-if="jobs.length > 0" class="sm-table" style="margin-top: 12px">
        <thead>
          <tr>
            <th>jobId</th>
            <th>itemId</th>
            <th>范围</th>
            <th>状态</th>
            <th>已完成月份</th>
            <th>检查点</th>
            <th>失败原因</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="job in jobs" :key="job.jobId">
            <td>{{ job.jobId }}</td>
            <td>{{ job.itemId }}</td>
            <td>{{ job.fromDate }} ~ {{ job.toDate }}</td>
            <td><StatusBadge :status="job.status" /></td>
            <td>{{ job.completedPeriods.join(', ') || '-' }}</td>
            <td>{{ job.currentCheckpoint ?? '-' }}</td>
            <td>{{ job.failureReasons.join('；') || '-' }}</td>
            <td>
              <button @click="runJob(job.jobId)">运行</button>
              <button @click="retryJob(job.jobId)">重试</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">暂无回填任务</p>
    </section>

    <section class="panel">
      <h2>配置版本审计（config/history，manifest 校验）</h2>
      <table v-if="history.length > 0" class="sm-table">
        <thead>
          <tr>
            <th>configVersion</th>
            <th>manifest 校验</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="entry in history" :key="entry.configVersion">
            <td>{{ entry.configVersion }}</td>
            <td><StatusBadge :status="entry.verified ? 'VERIFIED' : 'CORRUPT'" /></td>
            <td>{{ entry.message ?? 'OK' }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">暂无历史快照</p>
    </section>

    <section class="panel">
      <h2>Provider 能力（只读，无秘密）</h2>
      <table v-if="capabilities.length > 0" class="sm-table">
        <thead>
          <tr>
            <th>providerId</th>
            <th>类型</th>
            <th>接入方式</th>
            <th>实际来源</th>
            <th>当前数据</th>
            <th>历史数据</th>
            <th>已配置 rateKind</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="capability in capabilities" :key="capability.providerId">
            <td>{{ capability.providerId }}</td>
            <td>{{ capability.providerType }}</td>
            <td>{{ capability.accessMethod }}</td>
            <td>{{ capability.actualSourceName }}</td>
            <td>{{ capability.supportsCurrentData ? '支持' : '不支持' }}</td>
            <td>{{ capability.supportsHistoryData ? '支持' : '不支持' }}</td>
            <td>{{ capability.configuredRateKinds.join('、') || '-' }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">无 Provider 能力信息</p>
    </section>
  </div>
</template>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}
.form-grid label {
  display: flex;
  flex-direction: column;
  font-size: 13px;
  color: var(--sm-muted);
  gap: 4px;
}
.form-grid input,
.form-grid select {
  font-size: 13px;
  padding: 6px 8px;
  border: 1px solid var(--sm-border);
  border-radius: 4px;
}
button {
  font-size: 13px;
  padding: 6px 12px;
  border: 1px solid var(--sm-border);
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
  margin-right: 6px;
}
button:hover {
  background: #f0f2f5;
}
</style>
