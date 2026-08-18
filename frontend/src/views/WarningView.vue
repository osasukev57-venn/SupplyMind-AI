<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  acknowledgeWarning,
  evaluateWarning,
  fetchWarnings
} from '../api/warning'
import type { WarningView } from '../types/warning'
import StatusBadge from '../components/StatusBadge.vue'
import { grainLabel } from '../lib/labels'

/**
 * Warning page: view trigger results, evidence sources and handling status. All values are
 * rendered exactly as the backend returned them - the page never computes thresholds or risk.
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
const showEvaluate = ref(false)

function defaultRange() {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  from.value = start.toISOString().slice(0, 10)
  to.value = now.toISOString().slice(0, 10)
}

async function load() {
  if (!itemId.value || !from.value || !to.value) {
    errorMessage.value = '请填写监测项编号与查询日期范围'
    return
  }
  loading.value = true
  errorMessage.value = null
  const response = await fetchWarnings(itemId.value, from.value, to.value)
  warnings.value = response?.warnings ?? []
  if (!response) {
    errorMessage.value = '预警查询暂时不可用，请稍后重试'
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
    errorMessage.value = '请填写处置备注'
    return
  }
  errorMessage.value = null
  notice.value = null
  const ack = await acknowledgeWarning(itemId.value, ackFor.value.warningId, ackNote.value.trim())
  if (!ack) {
    errorMessage.value = '确认失败：服务暂时不可用，或备注不符合要求'
    return
  }
  notice.value = `已确认 ${ack.warningId}`
  ackFor.value = null
  await load()
}

async function submitEvaluate() {
  errorMessage.value = null
  notice.value = null
  if (!ruleId.value || !threshold.value || !evalPeriodStart.value || !evalPeriodEnd.value) {
    errorMessage.value = '请填写规则编号、阈值与周期起止'
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
    errorMessage.value = '试算暂时不可用，请稍后重试'
    return
  }
  evaluateResult.value = result.warning ?? null
  if (result.status === 'NOT_TRIGGERED') {
    notice.value = result.message ?? '未触发预警'
  }
  if (result.warning) {
    notice.value = `触发预警 ${result.warning.warningId}`
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
    <header class="page-head">
      <div class="page-eyebrow">运营分析</div>
      <h1 class="page-title">预警</h1>
      <p class="page-desc">查看预警触发结果、证据来源和处理状态。演示规则会明确标识，不用于正式业务判断。</p>
      <div class="page-actions">
        <button class="btn-secondary" @click="showEvaluate = !showEvaluate">
          {{ showEvaluate ? '收起规则试算' : '规则试算' }}
        </button>
      </div>
    </header>

    <div v-if="errorMessage" class="error-banner">{{ errorMessage }}</div>
    <div v-if="notice" class="demo-banner">{{ notice }}</div>

    <section class="section">
      <div class="section-head">
        <h2>预警查询</h2>
      </div>
      <div class="section-body">
        <div class="form-grid">
          <label>监测项编号
            <input v-model="itemId" />
          </label>
          <label>开始日期
            <input v-model="from" type="date" />
          </label>
          <label>结束日期
            <input v-model="to" type="date" />
          </label>
        </div>
        <div class="form-actions">
          <button class="btn-primary" @click="load">查询</button>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="section-head">
        <h2>预警列表</h2>
        <span class="muted">{{ warnings.length }} 条</span>
      </div>
      <div class="section-body flat">
        <div class="table-scroll">
          <table v-if="warnings.length > 0" class="sm-table">
            <thead>
              <tr>
                <th>预警编号</th>
                <th>规则</th>
                <th>周期</th>
                <th>当前值</th>
                <th>阈值</th>
                <th>风险等级</th>
                <th>规则性质</th>
                <th>处理状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="warning in warnings" :key="warning.warningId">
                <td class="id-cell">{{ warning.warningId }}</td>
                <td>{{ warning.ruleId }}@{{ warning.ruleVersion }}</td>
                <td>{{ warning.periodStart }} ~ {{ warning.periodEnd }}</td>
                <td class="num">{{ warning.currentValue }}</td>
                <td class="num">{{ warning.threshold }}</td>
                <td><StatusBadge :status="warning.riskLevel ?? 'UNKNOWN'" /></td>
                <td><StatusBadge :status="warning.demoRule ? 'DEMO' : 'FORMAL'" /></td>
                <td><StatusBadge :status="warning.acknowledged ? 'ACKNOWLEDGED' : 'PENDING'" /></td>
                <td>
                  <button v-if="!warning.acknowledged" class="btn-secondary" @click="openAck(warning)">
                    确认处理
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else-if="!loading" class="empty-state">所选范围内暂无预警</div>
        </div>
      </div>
    </section>

    <section v-if="ackFor" class="section">
      <div class="section-head">
        <h2>确认处理 {{ ackFor.warningId }}</h2>
      </div>
      <div class="section-body">
        <p class="muted">原预警记录保持不可改写，确认信息单独留存。</p>
        <label class="note-field">处置备注（最多 500 字）
          <textarea v-model="ackNote" rows="3" maxlength="500" />
        </label>
        <div class="form-actions">
          <button class="btn-primary" @click="submitAck">确认</button>
          <button class="btn-ghost" @click="ackFor = null">取消</button>
        </div>
      </div>
    </section>

    <section v-if="showEvaluate" class="section">
      <div class="section-head">
        <h2>规则试算</h2>
        <span class="muted">演示规则，仅用于了解预警触发逻辑</span>
      </div>
      <div class="section-body">
        <div class="form-grid">
          <label>规则编号
            <input v-model="ruleId" />
          </label>
          <label>规则类型
            <select v-model="ruleKind">
              <option value="PRICE_CHANGE">价格变化</option>
              <option value="RATE_CHANGE">汇率变化</option>
              <option value="COST_IMPACT">成本影响</option>
              <option value="DATA_QUALITY">数据质量</option>
            </select>
          </label>
          <label>粒度
            <select v-model="grain">
              <option value="month">按月</option>
              <option value="quarter">按季</option>
              <option value="halfyear">按半年</option>
              <option value="year">按年</option>
            </select>
          </label>
          <label>阈值
            <input v-model="threshold" placeholder="0.05" />
          </label>
          <label>方向
            <select v-model="direction">
              <option value="ABOVE">高于阈值</option>
              <option value="BELOW">低于阈值</option>
            </select>
          </label>
          <label>周期起
            <input v-model="evalPeriodStart" type="date" />
          </label>
          <label>周期止
            <input v-model="evalPeriodEnd" type="date" />
          </label>
        </div>
        <div class="form-actions">
          <button class="btn-secondary" @click="submitEvaluate">试算</button>
        </div>
        <div v-if="evaluateResult" class="demo-banner" style="margin-top: 12px">
          触发预警：{{ evaluateResult.ruleId }}（演示规则）
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
textarea {
  font-family: inherit;
  font-size: 13.5px;
  padding: 7px 9px;
  border: 1px solid var(--sm-border-strong);
  border-radius: var(--sm-radius);
  background: var(--sm-surface);
  color: var(--sm-text);
  width: 100%;
}
textarea:focus {
  outline: none;
  border-color: var(--sm-focus);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sm-focus) 22%, transparent);
}
.note-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12.5px;
  color: var(--sm-muted);
  max-width: 560px;
}
</style>
