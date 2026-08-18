<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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
import {
  fallbackReasonLabel,
  jobStatusLabel,
  modeLabel,
  providerLabel,
  routeLabel,
  sourceDisplayName
} from '../lib/labels'

/**
 * Dynamic configuration: manage monitored series, acquisition routes and history backfill.
 * The backend owns all validation, versioning and task identity; this page only sends
 * controlled requests and renders what the backend returns. No business computation here.
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
const replBackfillFrom = ref('')
const replBackfillTo = ref('')

// backfill form
const jobItemId = ref('')
const jobFrom = ref('')
const jobTo = ref('')

const loading = ref(true)
const showAddForm = ref(false)
const showReplaceForm = ref(false)

const enabledCount = computed(() => config.value?.items.filter((item) => item.enabled).length ?? 0)
const disabledCount = computed(() => config.value?.items.filter((item) => !item.enabled).length ?? 0)
const runningJobs = computed(() => jobs.value.filter((job) => job.status === 'RUNNING').length)

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
    errorMessage.value = '请填写监测项编号、名称、单位和基础币种'
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
    errorMessage.value = '新增失败：服务暂时不可用，请稍后重试，或该来源不支持此监测项'
    return
  }
  const currentNote = result.currentIntake
    ? `当前采集：${jobStatusLabel(result.currentIntake.status)}`
    : ''
  const jobNote = result.backfillJobs.length
    ? result.backfillJobs.map((job) => `回填 ${job.itemId}：${jobStatusLabel(job.status)}`).join('；')
    : '未设置历史回填范围'
  notice.value = `已新增 ${request.displayName}（${currentNote}${currentNote && jobNote ? '；' : ''}${jobNote}）`
  applyResult(result)
  newItemId.value = ''
  newDisplayName.value = ''
  newUnit.value = ''
  newBaseCurrency.value = ''
  newBackfillFrom.value = ''
  newBackfillTo.value = ''
  showAddForm.value = false
}

async function toggleEnabled(item: { itemId: string; displayName: string; enabled: boolean }) {
  errorMessage.value = null
  notice.value = null
  const result = await setEnabled(item.itemId, !item.enabled)
  if (!result) {
    errorMessage.value = '操作失败：服务暂时不可用，请稍后重试'
    return
  }
  notice.value = `${item.displayName} 已${item.enabled ? '停用' : '启用'}`
  applyResult(result)
}

async function submitReplace() {
  errorMessage.value = null
  notice.value = null
  if (!oldItemId.value || !replItemId.value || !replDisplayName.value) {
    errorMessage.value = '请填写旧监测项编号、新监测项编号和名称'
    return
  }
  if (!replBackfillFrom.value || !replBackfillTo.value) {
    errorMessage.value = '请填写历史回填的起止日期'
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
    backfillFrom: replBackfillFrom.value,
    backfillTo: replBackfillTo.value
  }
  const result = await replaceItem({ oldItemId: oldItemId.value, newItem: base })
  if (!result) {
    errorMessage.value = '替换失败：服务暂时不可用，请稍后重试'
    return
  }
  const currentNote = result.currentIntake
    ? `当前采集：${jobStatusLabel(result.currentIntake.status)}`
    : ''
  const jobNote = result.backfillJobs
    .map((job) => `回填 ${job.itemId}：${jobStatusLabel(job.status)}`)
    .join('；')
  notice.value = `已替换 ${oldItemId.value} → ${replItemId.value}（${currentNote}${currentNote && jobNote ? '；' : ''}${jobNote}）`
  applyResult(result)
  oldItemId.value = ''
  replItemId.value = ''
  replDisplayName.value = ''
  replBackfillFrom.value = ''
  replBackfillTo.value = ''
  showReplaceForm.value = false
}

async function submitCreateJob() {
  errorMessage.value = null
  notice.value = null
  if (!jobItemId.value || !jobFrom.value || !jobTo.value) {
    errorMessage.value = '请填写监测项编号与回填日期范围'
    return
  }
  const job = await createBackfillJob(jobItemId.value, jobFrom.value, jobTo.value)
  if (!job) {
    errorMessage.value = '创建回填任务失败：服务暂时不可用，请稍后重试'
    return
  }
  notice.value = `任务 ${job.itemId}：${jobStatusLabel(job.status)}`
  await loadJobs()
}

async function runJob(jobId: string) {
  errorMessage.value = null
  notice.value = null
  const job = await runBackfillJob(jobId)
  if (!job) {
    errorMessage.value = '运行任务失败：服务暂时不可用，请稍后重试'
    return
  }
  notice.value = `任务 ${job.itemId}：${jobStatusLabel(job.status)}${job.failureReasons.length ? '（' + job.failureReasons.join('；') + '）' : ''}`
  await loadJobs()
}

async function retryJob(jobId: string) {
  errorMessage.value = null
  notice.value = null
  const job = await retryBackfillJob(jobId)
  if (!job) {
    errorMessage.value = '重试任务失败：服务暂时不可用，请稍后重试'
    return
  }
  notice.value = `任务 ${job.itemId} 重试后：${jobStatusLabel(job.status)}`
  await loadJobs()
}

onMounted(refresh)
</script>

<template>
  <div>
    <header class="page-head">
      <div class="page-eyebrow">数据管理</div>
      <h1 class="page-title">动态配置</h1>
      <p class="page-desc">集中管理监测范围、采集路线和历史回填。配置生效后，监测面板将自动更新，已有历史数据继续保留。</p>
      <div class="page-actions">
        <button class="btn-primary" @click="showAddForm = !showAddForm; showReplaceForm = false">
          {{ showAddForm ? '收起新增表单' : '新增监测项' }}
        </button>
        <button class="btn-secondary" @click="showReplaceForm = !showReplaceForm; showAddForm = false">
          {{ showReplaceForm ? '收起替换表单' : '替换监测项' }}
        </button>
      </div>
    </header>

    <div v-if="errorMessage" class="error-banner">{{ errorMessage }}</div>
    <div v-if="notice" class="demo-banner">{{ notice }}</div>

    <div v-if="config" class="summary-strip">
      <div class="summary-item">
        <div class="summary-label">配置版本</div>
        <div class="summary-value">{{ config.configVersion }}</div>
      </div>
      <div class="summary-item">
        <div class="summary-label">已启用</div>
        <div class="summary-value">{{ enabledCount }}</div>
      </div>
      <div class="summary-item">
        <div class="summary-label">已停用</div>
        <div class="summary-value">{{ disabledCount }}</div>
      </div>
      <div class="summary-item">
        <div class="summary-label">运行中的任务</div>
        <div class="summary-value">{{ runningJobs }}</div>
      </div>
      <div class="summary-item">
        <div class="summary-label">运行状态</div>
        <div class="summary-value">
          <StatusBadge :status="config.mode ?? 'FORMAL'" />
        </div>
      </div>
    </div>

    <section class="section">
      <div class="section-head">
        <h2>监测项列表</h2>
        <span class="muted">共 {{ config?.items.length ?? 0 }} 项</span>
      </div>
      <div class="section-body flat">
        <div class="table-scroll">
          <table class="sm-table responsive-data-table">
            <thead>
              <tr>
                <th>监测项</th>
                <th>状态</th>
                <th>数据来源</th>
                <th>接入方式</th>
                <th>采集路线</th>
                <th>单位</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in config?.items" :key="item.itemId">
                <td>
                  <div>{{ item.displayName }}</div>
                  <div class="id-cell muted">{{ item.itemId }}</div>
                </td>
                <td><StatusBadge :status="item.enabled ? 'ENABLED' : 'DISABLED'" /></td>
                <td>{{ sourceDisplayName(item.actualSourceName) }}</td>
                <td>{{ providerLabel(item.providerType) }}</td>
                <td>
                  {{ routeLabel(item.routeDecision) }}
                  <span v-if="item.fallbackReason" class="muted">（{{ fallbackReasonLabel(item.fallbackReason) }}）</span>
                </td>
                <td>{{ item.unit }}</td>
                <td>
                  <button
                    v-if="item.enabled"
                    class="btn-secondary"
                    @click="toggleEnabled(item)"
                  >停用</button>
                  <button
                    v-else
                    class="btn-secondary"
                    @click="toggleEnabled(item)"
                  >启用</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="!loading && (!config || config.items.length === 0)" class="empty-state">
          暂无监测项，点击“新增监测项”开始配置
        </div>
      </div>
    </section>

    <section v-if="showAddForm" class="section">
      <div class="section-head">
        <h2>新增监测项</h2>
      </div>
      <div class="section-body">
        <div class="form-grid">
          <label>监测项编号
            <input v-model="newItemId" placeholder="如 FX.GBP.CNY.PBOC_MID" />
          </label>
          <label>名称
            <input v-model="newDisplayName" placeholder="如 英镑/人民币中间价" />
          </label>
          <label>来源意图
            <input v-model="newSourceIntent" placeholder="PBOC" />
          </label>
          <label>接入方式
            <select v-model="newProviderType">
              <option value="official_web">官方网站</option>
              <option value="manual">人工录入</option>
              <option value="local_import">本地导入</option>
            </select>
          </label>
          <label>数据类型
            <input v-model="newRateKind" placeholder="人民币汇率中间价 或 材料价格" />
          </label>
          <label>单位
            <input v-model="newUnit" placeholder="如 CNY/1 GBP 或 元/吨" />
          </label>
          <label>基础币种
            <input v-model="newBaseCurrency" placeholder="如 GBP 或 CNY" />
          </label>
          <label>历史回填起点
            <input v-model="newBackfillFrom" type="date" />
          </label>
          <label>历史回填终点
            <input v-model="newBackfillTo" type="date" />
          </label>
        </div>
        <div class="form-actions">
          <button class="btn-primary" @click="submitAdd">新增并立即采集</button>
          <button class="btn-ghost" @click="showAddForm = false">取消</button>
        </div>
      </div>
    </section>

    <section v-if="showReplaceForm" class="section">
      <div class="section-head">
        <h2>替换监测项</h2>
      </div>
      <div class="section-body">
        <p class="muted">替换后旧监测项将停用，历史数据保留并可继续查询。</p>
        <div class="form-grid">
          <label>旧监测项编号
            <input v-model="oldItemId" placeholder="如 MAT.AZ91D.SMM" />
          </label>
          <label>新监测项编号
            <input v-model="replItemId" placeholder="如 MAT.REPL-01.SMM" />
          </label>
          <label>新名称
            <input v-model="replDisplayName" placeholder="如 AZ91D替代材料（SMM意图）" />
          </label>
          <label>来源意图
            <input v-model="replSourceIntent" placeholder="SMM 或 Asian Metal" />
          </label>
          <label>历史回填起点
            <input v-model="replBackfillFrom" type="date" />
          </label>
          <label>历史回填终点
            <input v-model="replBackfillTo" type="date" />
          </label>
        </div>
        <div class="form-actions">
          <button class="btn-primary" @click="submitReplace">执行替换</button>
          <button class="btn-ghost" @click="showReplaceForm = false">取消</button>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="section-head">
        <h2>历史回填任务</h2>
        <span class="muted">按监测项与日期范围运行</span>
      </div>
      <div class="section-body">
        <div class="form-grid" style="margin-bottom: 12px">
          <label>监测项编号
            <input v-model="jobItemId" placeholder="如 FX.GBP.CNY.PBOC_MID" />
          </label>
          <label>开始日期
            <input v-model="jobFrom" type="date" />
          </label>
          <label>结束日期
            <input v-model="jobTo" type="date" />
          </label>
        </div>
        <button class="btn-secondary" @click="submitCreateJob">创建回填任务</button>
        <div class="table-scroll" style="margin-top: 12px">
          <table v-if="jobs.length > 0" class="sm-table">
            <thead>
              <tr>
                <th>监测项编号</th>
                <th>范围</th>
                <th>状态</th>
                <th>已完成月份</th>
                <th>检查点</th>
                <th>说明</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="job in jobs" :key="job.jobId">
                <td class="id-cell">{{ job.itemId }}</td>
                <td>{{ job.fromDate }} ~ {{ job.toDate }}</td>
                <td><StatusBadge :status="job.status" /></td>
                <td>{{ job.completedPeriods.join(', ') || '—' }}</td>
                <td class="num">{{ job.currentCheckpoint ?? '—' }}</td>
                <td class="cell-truncate">{{ job.failureReasons.join('；') || '—' }}</td>
                <td>
                  <button class="btn-secondary" @click="runJob(job.jobId)">运行</button>
                  <button class="btn-ghost" @click="retryJob(job.jobId)">重试</button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty-state">暂无回填任务</div>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="section-head">
        <h2>配置历史</h2>
        <span class="muted">每次配置变更的版本记录</span>
      </div>
      <div class="section-body flat">
        <div class="table-scroll">
          <table v-if="history.length > 0" class="sm-table">
            <thead>
              <tr>
                <th>版本</th>
                <th>校验状态</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in history" :key="entry.configVersion">
                <td class="num">{{ entry.configVersion }}</td>
                <td><StatusBadge :status="entry.verified ? 'VERIFIED' : 'CORRUPT'" /></td>
                <td>{{ entry.message ?? '正常' }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty-state">暂无配置历史</div>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="section-head">
        <h2>可用数据来源</h2>
        <span class="muted">各接入方式当前支持的数据类型</span>
      </div>
      <div class="section-body flat">
        <div class="table-scroll">
          <table v-if="capabilities.length > 0" class="sm-table responsive-data-table">
            <thead>
              <tr>
                <th>接入方式</th>
                <th>数据来源</th>
                <th>当前数据</th>
                <th>历史数据</th>
                <th>已支持类型</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="capability in capabilities" :key="capability.providerId">
                <td>{{ providerLabel(capability.providerType) }}</td>
                <td>{{ sourceDisplayName(capability.actualSourceName) }}</td>
                <td>{{ capability.supportsCurrentData ? '支持' : '不支持' }}</td>
                <td>{{ capability.supportsHistoryData ? '支持' : '不支持' }}</td>
                <td>{{ capability.configuredRateKinds.join('、') || '—' }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty-state">暂无来源信息</div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
</style>
