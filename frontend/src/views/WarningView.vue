<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  acknowledgeWarning,
  evaluateAllWarningRules,
  fetchWarningAck,
  fetchWarningRules,
  fetchWarnings,
  resetWarningRule,
  updateWarningRule
} from '../api/warning'
import type { AckView, RuleEvaluationView, RuleView, WarningView } from '../types/warning'
import StatusBadge from '../components/StatusBadge.vue'

/**
 * Formal warning console. Java owns defaults, comparisons, percentages and trigger decisions.
 * This component only submits user settings and renders display-ready backend values.
 */

const rules = ref<RuleView[]>([])
const evaluations = ref<RuleEvaluationView[]>([])
const itemId = ref('')
const from = ref('')
const to = ref('')
const warnings = ref<WarningView[]>([])
const errorMessage = ref<string | null>(null)
const notice = ref<string | null>(null)
const loading = ref(false)
const evaluating = ref(false)

const editItemId = ref('')
const editLookbackDays = ref(1)
const editThresholdPercent = ref('1')
const editDirection = ref<'UP' | 'DOWN' | 'BOTH'>('BOTH')
const editEnabled = ref(true)
const editDescription = ref('')

const ackNote = ref('')
const ackFor = ref<WarningView | null>(null)
const ackDetail = ref<AckView | null>(null)
const ackDetailFor = ref<WarningView | null>(null)
const ackDetailLoading = ref(false)

const selectedRule = computed(() => rules.value.find(rule => rule.itemId === editItemId.value) ?? null)
const evaluationByItem = computed(() => new Map(evaluations.value.map(result => [result.rule.itemId, result])))

function defaultRange() {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth() - 2, 1)
  from.value = start.toISOString().slice(0, 10)
  to.value = now.toISOString().slice(0, 10)
}

function directionLabel(direction: string) {
  if (direction === 'UP') return '仅上涨'
  if (direction === 'DOWN') return '仅下跌'
  return '上涨或下跌'
}

function originLabel(origin: string) {
  return origin === 'USER_CUSTOM' ? '用户自定义' : '系统默认'
}

function evaluationStatusLabel(status: string) {
  const labels: Record<string, string> = {
    TRIGGERED: '已触发预警',
    NOT_TRIGGERED: '当前未触发',
    INSUFFICIENT_DATA: '数据不足',
    INVALID_DATA: '数据无效',
    DISABLED: '规则已停用'
  }
  return labels[status] ?? status
}

function selectRule(item: RuleView) {
  editItemId.value = item.itemId
  editLookbackDays.value = item.lookbackDays
  editThresholdPercent.value = item.thresholdPercent
  editDirection.value = item.direction
  editEnabled.value = item.enabled
  editDescription.value = item.description
}

async function loadRules() {
  const response = await fetchWarningRules()
  if (!response) {
    errorMessage.value = '预警规则暂时不可用，请稍后重试'
    return false
  }
  rules.value = response.rules
  if (!itemId.value && rules.value.length > 0) {
    itemId.value = rules.value[0].itemId
  }
  const current = rules.value.find(rule => rule.itemId === editItemId.value) ?? rules.value[0]
  if (current) selectRule(current)
  return true
}

async function loadWarnings() {
  if (!itemId.value || !from.value || !to.value) return
  const response = await fetchWarnings(itemId.value, from.value, to.value)
  warnings.value = response?.warnings ?? []
  if (!response) errorMessage.value = '预警记录查询暂时不可用，请稍后重试'
}

async function evaluateAll() {
  evaluating.value = true
  errorMessage.value = null
  const result = await evaluateAllWarningRules()
  evaluating.value = false
  if (!result) {
    errorMessage.value = '正式预警计算暂时不可用，请稍后重试'
    return
  }
  evaluations.value = result
  const triggered = result.filter(entry => entry.status === 'TRIGGERED').length
  notice.value = `已完成 ${result.length} 个监测项的预警计算，当前触发 ${triggered} 项`
  await loadWarnings()
}

async function refreshAll() {
  loading.value = true
  errorMessage.value = null
  const ready = await loadRules()
  if (ready) {
    await evaluateAll()
  }
  loading.value = false
}

async function saveRule() {
  errorMessage.value = null
  notice.value = null
  if (!editItemId.value || !editThresholdPercent.value || editLookbackDays.value < 1) {
    errorMessage.value = '请选择监测项，并填写有效周期与阈值'
    return
  }
  const response = await updateWarningRule(editItemId.value, {
    lookbackDays: editLookbackDays.value,
    thresholdPercent: editThresholdPercent.value,
    direction: editDirection.value,
    enabled: editEnabled.value,
    description: editDescription.value.trim()
  })
  if (!response) {
    errorMessage.value = '规则保存失败，请检查阈值后重试'
    return
  }
  notice.value = `已保存 ${response.rule.displayName} 的自定义规则并立即完成评估`
  await loadRules()
  evaluations.value = [
    ...evaluations.value.filter(entry => entry.rule.itemId !== response.evaluation.rule.itemId),
    response.evaluation
  ]
  itemId.value = response.rule.itemId
  await loadWarnings()
}

async function restoreDefault() {
  if (!editItemId.value) return
  errorMessage.value = null
  const response = await resetWarningRule(editItemId.value)
  if (!response) {
    errorMessage.value = '恢复默认规则失败，请稍后重试'
    return
  }
  notice.value = `已恢复 ${response.rule.displayName} 的系统默认规则并立即完成评估`
  await loadRules()
  evaluations.value = [
    ...evaluations.value.filter(entry => entry.rule.itemId !== response.evaluation.rule.itemId),
    response.evaluation
  ]
  itemId.value = response.rule.itemId
  await loadWarnings()
}

function openAck(warning: WarningView) {
  ackFor.value = warning
  ackNote.value = ''
}

async function viewAck(warning: WarningView) {
  ackDetailLoading.value = true
  errorMessage.value = null
  const response = await fetchWarningAck(warning.itemId, warning.warningId)
  ackDetailLoading.value = false
  if (!response?.acknowledged || !response.ack) {
    errorMessage.value = '确认记录不可用或未通过完整性校验'
    return
  }
  ackDetailFor.value = warning
  ackDetail.value = response.ack
}

async function submitAck() {
  if (!ackFor.value || !ackNote.value.trim()) {
    errorMessage.value = '请填写处置备注'
    return
  }
  const ack = await acknowledgeWarning(ackFor.value.itemId, ackFor.value.warningId, ackNote.value.trim())
  if (!ack) {
    errorMessage.value = '确认失败：服务暂时不可用，或备注不符合要求'
    return
  }
  notice.value = `已确认 ${ack.warningId}，确认记录已保存，可随时查询`
  ackDetailFor.value = ackFor.value
  ackDetail.value = ack
  ackFor.value = null
  await loadWarnings()
}

onMounted(() => {
  defaultRange()
  void refreshAll()
})
</script>

<template>
  <div>
    <header class="page-head">
      <div class="page-eyebrow">运营分析</div>
      <h1 class="page-title">预警</h1>
      <p class="page-desc">
        每个已启用监测项自动绑定可用的默认变化率规则。系统只使用已发布、已校验数据计算，
        数据不足时明确提示且不会补零；你也可以按监测项覆盖周期、阈值和方向。
      </p>
      <div class="page-actions">
        <button class="btn-primary" :disabled="evaluating" @click="evaluateAll">
          {{ evaluating ? '正在计算…' : '立即执行全部预警计算' }}
        </button>
      </div>
    </header>

    <div v-if="errorMessage" class="error-banner">{{ errorMessage }}</div>
    <div v-if="notice" class="demo-banner">{{ notice }}</div>

    <section class="section">
      <div class="section-head">
        <h2>监测项预警状态</h2>
        <span class="muted">{{ rules.length }} 个有效监测项</span>
      </div>
      <div class="section-body flat">
        <div class="table-scroll">
          <table v-if="rules.length" class="sm-table">
            <thead>
              <tr>
                <th>监测项</th>
                <th>有效规则</th>
                <th>比较口径</th>
                <th>最新计算</th>
                <th>变化</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="rule in rules" :key="rule.itemId">
                <td>
                  <strong>{{ rule.displayName }}</strong>
                  <div class="muted id-cell">{{ rule.itemId }}</div>
                </td>
                <td>
                  <StatusBadge :status="rule.origin" />
                  <div class="muted">{{ rule.ruleVersion }}</div>
                </td>
                <td>
                  {{ rule.lookbackDays }} 个自然日 · {{ directionLabel(rule.direction) }} ·
                  {{ rule.thresholdPercent }}%
                </td>
                <td>
                  <StatusBadge :status="evaluationByItem.get(rule.itemId)?.status ?? 'NO_DATA'" />
                  <div class="muted">{{ evaluationByItem.get(rule.itemId)?.message ?? '尚未计算' }}</div>
                </td>
                <td class="num">
                  <template v-if="evaluationByItem.get(rule.itemId)?.changePercent != null">
                    {{ evaluationByItem.get(rule.itemId)?.changePercent }}%
                  </template>
                  <template v-else>—</template>
                </td>
                <td>
                  <button class="btn-secondary" @click="selectRule(rule)">修改规则</button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else-if="!loading" class="empty-state">暂无可配置的监测项</div>
        </div>
      </div>
    </section>

    <section v-if="selectedRule" class="section">
      <div class="section-head">
        <h2>预警规则设置</h2>
        <span class="muted">系统默认可直接使用；保存后仅覆盖当前监测项</span>
      </div>
      <div class="section-body">
        <div class="form-grid">
          <label>监测项
            <select v-model="editItemId" @change="selectedRule && selectRule(selectedRule)">
              <option v-for="rule in rules" :key="rule.itemId" :value="rule.itemId">
                {{ rule.displayName }}
              </option>
            </select>
          </label>
          <label>比较周期（自然日）
            <input v-model.number="editLookbackDays" type="number" min="1" max="365" />
          </label>
          <label>变化率阈值（%）
            <input v-model="editThresholdPercent" inputmode="decimal" placeholder="如 5" />
          </label>
          <label>触发方向
            <select v-model="editDirection">
              <option value="BOTH">上涨或下跌</option>
              <option value="UP">仅上涨</option>
              <option value="DOWN">仅下跌</option>
            </select>
          </label>
          <label>规则状态
            <select v-model="editEnabled">
              <option :value="true">启用</option>
              <option :value="false">停用</option>
            </select>
          </label>
          <label>规则说明
            <input v-model="editDescription" />
          </label>
        </div>
        <p class="muted">
          默认规则：汇率等非材料数据 1 个自然日、1%；材料数据 7 个自然日、5%。
          达到阈值即触发，缺少边界数据时显示“数据不足”。
        </p>
        <div class="form-actions">
          <button class="btn-primary" @click="saveRule">保存并立即评估</button>
          <button class="btn-secondary" @click="restoreDefault">恢复系统默认</button>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="section-head">
        <h2>正式预警记录</h2>
        <span class="muted">{{ warnings.length }} 条</span>
      </div>
      <div class="section-body">
        <div class="form-grid compact-query">
          <label>监测项
            <select v-model="itemId" @change="loadWarnings">
              <option v-for="rule in rules" :key="rule.itemId" :value="rule.itemId">
                {{ rule.displayName }}
              </option>
            </select>
          </label>
          <label>开始日期
            <input v-model="from" type="date" />
          </label>
          <label>结束日期
            <input v-model="to" type="date" />
          </label>
        </div>
        <div class="form-actions">
          <button class="btn-secondary" @click="loadWarnings">查询记录</button>
        </div>
        <div class="table-scroll">
          <table v-if="warnings.length" class="sm-table">
            <thead>
              <tr>
                <th>预警编号</th>
                <th>规则</th>
                <th>比较日期</th>
                <th>基准值 → 当前值</th>
                <th>阈值</th>
                <th>风险</th>
                <th>处理状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="warning in warnings" :key="warning.warningId">
                <td class="id-cell">{{ warning.warningId }}</td>
                <td>{{ warning.ruleId }}<div class="muted">{{ warning.ruleVersion }}</div></td>
                <td>{{ warning.periodStart }} → {{ warning.periodEnd }}</td>
                <td class="num">{{ warning.baselineValue }} → {{ warning.currentValue }}</td>
                <td class="num">{{ warning.thresholdPercent }}%</td>
                <td><StatusBadge :status="warning.riskLevel ?? 'UNKNOWN'" /></td>
                <td><StatusBadge :status="warning.acknowledged ? 'ACKNOWLEDGED' : 'PENDING'" /></td>
                <td>
                  <button v-if="!warning.acknowledged" class="btn-secondary" @click="openAck(warning)">
                    确认处理
                  </button>
                  <button v-else class="btn-secondary" :disabled="ackDetailLoading" @click="viewAck(warning)">
                    查看确认记录
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty-state">所选范围内暂无已触发的正式预警</div>
        </div>
      </div>
    </section>

    <section v-if="ackDetailFor && ackDetail" class="section ack-audit">
      <div class="section-head">
        <h2>确认记录</h2>
        <button class="btn-ghost" @click="ackDetailFor = null; ackDetail = null">关闭</button>
      </div>
      <div class="section-body">
        <div class="ack-grid">
          <div><span>预警编号</span><strong>{{ ackDetail.warningId }}</strong></div>
          <div><span>处理状态</span><StatusBadge :status="ackDetail.status" /></div>
          <div><span>确认时间</span><strong>{{ ackDetail.acknowledgedAt ?? '—' }}</strong></div>
          <div class="ack-note"><span>处置备注</span><strong>{{ ackDetail.dispositionNote }}</strong></div>
        </div>
        <details class="audit-details">
          <summary>查看审计绑定</summary>
          <div class="id-cell">原预警：{{ ackDetail.warningRef }}</div>
          <div class="id-cell">原文件 SHA-256：{{ ackDetail.warningFileSha256 }}</div>
          <div v-if="ackDetailFor.ackRef" class="id-cell">确认记录：{{ ackDetailFor.ackRef }}</div>
        </details>
      </div>
    </section>
    <section v-if="ackFor" class="section">
      <div class="section-head"><h2>确认处理 {{ ackFor.warningId }}</h2></div>
      <div class="section-body">
        <p class="muted">原预警记录保持不可改写，确认信息以独立审计记录留存。</p>
        <label class="note-field">处置备注（最多 500 字）
          <textarea v-model="ackNote" rows="3" maxlength="500" />
        </label>
        <div class="form-actions">
          <button class="btn-primary" @click="submitAck">确认</button>
          <button class="btn-ghost" @click="ackFor = null">取消</button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.compact-query { margin-bottom: 12px; }
.id-cell { max-width: 230px; overflow-wrap: anywhere; }
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
.ack-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}
.ack-grid > div {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  border: 1px solid var(--sm-border);
  border-radius: var(--sm-radius);
  background: var(--sm-surface-subtle);
}
.ack-grid span { color: var(--sm-muted); font-size: 12px; }
.ack-note { grid-column: 1 / -1; }
.audit-details { margin-top: 14px; color: var(--sm-muted); }
.audit-details .id-cell { margin-top: 8px; }
@media (max-width: 760px) { .ack-grid { grid-template-columns: 1fr; } }
.note-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12.5px;
  color: var(--sm-muted);
  max-width: 680px;
}
</style>
