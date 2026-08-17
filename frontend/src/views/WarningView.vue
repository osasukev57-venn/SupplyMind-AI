<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  acknowledgeWarning,
  evaluateWarning,
  fetchWarnings
} from '../api/warning'
import type { WarningView } from '../types/warning'
import StatusBadge from '../components/StatusBadge.vue'

/**
 * D8-T02 warning page. Warning evidence is immutable backend JSON; acknowledgement writes a
 * DEC-061 sidecar. The page never computes thresholds/risk levels - everything is displayed as
 * the backend returned it.
 */

const itemId = ref('MAT.ADC12.SMM')
const from = ref('')
const to = ref('')
const warnings = ref<WarningView[]>([])
const errorMessage = ref<string | null>(null)
const notice = ref<string | null>(null)
const loading = ref(false)

// acknowledge form
const ackNote = ref('')
const ackFor = ref<WarningView | null>(null)

// evaluate form
const ruleId = ref('demo-price-change-x')
const ruleKind = ref('PRICE_CHANGE')
const threshold = ref('0.05')
const direction = ref('ABOVE')
const grain = ref('month')
const evalPeriodStart = ref('')
const evalPeriodEnd = ref('')
const evaluateResult = ref<WarningView | null>(null)

function defaultRange() {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  from.value = start.toISOString().slice(0, 10)
  to.value = now.toISOString().slice(0, 10)
}

async function load() {
  if (!itemId.value || !from.value || !to.value) {
    errorMessage.value = '必填：itemId、from、to'
    return
  }
  loading.value = true
  errorMessage.value = null
  const response = await fetchWarnings(itemId.value, from.value, to.value)
  warnings.value = response?.warnings ?? []
  if (!response) {
    errorMessage.value = '预警查询失败：后端不可用或参数被拒绝'
  }
  loading.value = false
}

function openAck(warning: WarningView) {
  ackFor.value = warning
  ackNote.value = ''
}

async function submitAck() {
  if (!ackFor.value) {
    return
  }
  if (!ackNote.value.trim()) {
    errorMessage.value = '处置备注不能为空'
    return
  }
  errorMessage.value = null
  notice.value = null
  const ack = await acknowledgeWarning(itemId.value, ackFor.value.warningId, ackNote.value.trim())
  if (!ack) {
    errorMessage.value = '确认失败：后端不可用或备注被拒绝'
    return
  }
  notice.value = `已确认 ${ack.warningId}（${ack.status}）`
  ackFor.value = null
  await load()
}

async function submitEvaluate() {
  errorMessage.value = null
  notice.value = null
  if (!ruleId.value || !threshold.value || !evalPeriodStart.value || !evalPeriodEnd.value) {
    errorMessage.value = '规则求值必填：ruleId、阈值、周期起止'
    return
  }
  const result = await evaluateWarning({
    ruleId: ruleId.value,
    ruleKind: ruleKind.value,
    itemId: itemId.value,
    grain: grain.value,
    threshold: threshold.value,
    direction: direction.value,
    periodStart: evalPeriodStart.value,
    periodEnd: evalPeriodEnd.value
  })
  if (!result) {
    errorMessage.value = '规则求值失败：后端不可用或参数被拒绝'
    return
  }
  evaluateResult.value = result.warning ?? null
  if (result.status === 'NOT_TRIGGERED') {
    notice.value = result.message ?? '未触发'
  }
  if (result.warning) {
    notice.value = `触发预警 ${result.warning.warningId}（demoRule=${result.warning.demoRule}）`
    await load()
  }
}

onMounted(() => {
  defaultRange()
  void load()
})
</script>

<template>
  <div>
    <h1>预警</h1>
    <p class="muted">
      D8-T02：预警由 Java 确定性规则生成；EXT-07/EXT-08 阈值未确认，所有规则均明确标记
      TEST/DEMO。确认只写 DEC-061 sidecar，原预警证据不可改写。
    </p>

    <div v-if="errorMessage" class="error-banner">{{ errorMessage }}</div>
    <div v-if="notice" class="demo-banner">{{ notice }}</div>

    <section class="panel">
      <h2>预警查询（真实 from/to 范围）</h2>
      <div class="form-grid">
        <label>itemId <input v-model="itemId" /></label>
        <label>from <input v-model="from" type="date" /></label>
        <label>to <input v-model="to" type="date" /></label>
      </div>
      <button @click="load">查询</button>

      <table v-if="warnings.length > 0" class="sm-table" style="margin-top: 12px">
        <thead>
          <tr>
            <th>warningId</th>
            <th>规则</th>
            <th>周期</th>
            <th>当前值</th>
            <th>阈值</th>
            <th>级别</th>
            <th>demo</th>
            <th>确认</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="warning in warnings" :key="warning.warningId">
            <td>{{ warning.warningId }}</td>
            <td>{{ warning.ruleId }}@{{ warning.ruleVersion }}</td>
            <td>{{ warning.periodStart }} ~ {{ warning.periodEnd }}</td>
            <td>{{ warning.currentValue }}</td>
            <td>{{ warning.threshold }}</td>
            <td><StatusBadge :status="warning.riskLevel ?? 'UNKNOWN'" /></td>
            <td><StatusBadge :status="warning.demoRule ? 'DEMO' : 'FORMAL'" /></td>
            <td><StatusBadge :status="warning.acknowledged ? 'ACKNOWLEDGED' : 'OPEN'" /></td>
            <td>
              <button v-if="!warning.acknowledged" @click="openAck(warning)">确认</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="!loading" class="muted">所选范围内无预警</p>
    </section>

    <section v-if="ackFor" class="panel">
      <h2>确认预警 {{ ackFor.warningId }}</h2>
      <p class="muted">原预警证据不可改写；确认写入独立 sidecar。</p>
      <label>处置备注（<=500 字符，禁止路径/分隔符）
        <textarea v-model="ackNote" rows="3" style="width: 100%" maxlength="500" />
      </label>
      <button @click="submitAck">确认</button>
      <button @click="ackFor = null">取消</button>
    </section>

    <section class="panel">
      <h2>规则求值（v1 demo 规则；EXT-07/EXT-08 保持开放）</h2>
      <div class="form-grid">
        <label>ruleId <input v-model="ruleId" /></label>
        <label>ruleKind
          <select v-model="ruleKind">
            <option value="PRICE_CHANGE">PRICE_CHANGE</option>
            <option value="RATE_CHANGE">RATE_CHANGE</option>
            <option value="COST_IMPACT">COST_IMPACT</option>
            <option value="DATA_QUALITY">DATA_QUALITY</option>
          </select>
        </label>
        <label>grain
          <select v-model="grain">
            <option value="month">month</option>
            <option value="quarter">quarter</option>
            <option value="halfyear">halfyear</option>
            <option value="year">year</option>
          </select>
        </label>
        <label>阈值 <input v-model="threshold" placeholder="0.05" /></label>
        <label>方向
          <select v-model="direction">
            <option value="ABOVE">ABOVE</option>
            <option value="BELOW">BELOW</option>
          </select>
        </label>
        <label>周期起 <input v-model="evalPeriodStart" type="date" /></label>
        <label>周期止 <input v-model="evalPeriodEnd" type="date" /></label>
      </div>
      <button @click="submitEvaluate">执行（demoRule=true）</button>
      <div v-if="evaluateResult" class="demo-banner" style="margin-top: 12px">
        触发：{{ evaluateResult.ruleId }} / {{ evaluateResult.warningId }} / demoRule={{ evaluateResult.demoRule }}
      </div>
    </section>
  </div>
</template>

<style scoped>
button {
  margin-right: 6px;
}
textarea {
  font-family: inherit;
  font-size: 13.5px;
  padding: 7px 9px;
  border: 1px solid var(--sm-border-strong);
  border-radius: var(--sm-radius-sm);
  background: var(--sm-surface);
  color: var(--sm-text);
}
textarea:focus {
  outline: none;
  border-color: var(--sm-focus);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sm-focus) 22%, transparent);
}
</style>
