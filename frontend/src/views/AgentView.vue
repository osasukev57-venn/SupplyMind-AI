<script setup lang="ts">
import { ref } from 'vue'
import { queryAgent } from '../api/agent'
import type { AgentQueryResponse } from '../types/agent'
import StatusBadge from '../components/StatusBadge.vue'

/**
 * D8-T03 industrial supply-chain Agent workbench. The page sends the user question to the
 * backend orchestration (controlled tool calls + EvidencePack + verified report); it renders
 * the returned facts/tool timeline/claims/limitations and NEVER computes values, risk levels,
 * recommendations or calculation results itself. LLM vs Java-template output is honestly
 * labelled via generatedBy/degraded.
 */

const question = ref('')
const itemId = ref('')
const mode = ref('FORMAL')
const loading = ref(false)
const errorMessage = ref<string | null>(null)
const result = ref<AgentQueryResponse | null>(null)

async function ask() {
  if (!question.value.trim()) {
    errorMessage.value = '请输入问题'
    return
  }
  loading.value = true
  errorMessage.value = null
  result.value = null
  const response = await queryAgent({
    question: question.value.trim(),
    itemId: itemId.value || undefined,
    mode: mode.value
  })
  loading.value = false
  if (!response) {
    errorMessage.value = 'Agent 查询失败：后端不可用或参数被拒绝'
    return
  }
  result.value = response
}

function modeLabel() {
  return result.value?.degraded ? 'JAVA_TEMPLATE' : (result.value?.generatedBy ?? 'UNKNOWN')
}

function evidenceKind(ref: string): 'history' | 'quality' | 'warning' | 'other' {
  if (ref.startsWith('processed/daily') || ref.startsWith('processed/aggregate')) {
    return 'history'
  }
  if (ref.startsWith('warning/')) {
    return 'warning'
  }
  if (ref.startsWith('report/')) {
    return 'quality'
  }
  return 'other'
}
</script>

<template>
  <div>
    <h1>Agent 工作台</h1>
    <p class="muted">
      D8-T03：问题由后端编排为受控只读工具调用，所有数值来自 Java 事实；LLM 失败时生成
      Java 模板报告（明确标记 degraded）。
    </p>

    <div v-if="errorMessage" class="error-banner">{{ errorMessage }}</div>

    <section class="panel">
      <h2>提问</h2>
      <div class="form-grid">
        <label class="wide">问题
          <textarea v-model="question" rows="2" placeholder="如：分析 ADC12 近期上涨风险" />
        </label>
        <label>itemId（可选）<input v-model="itemId" placeholder="如 MAT.ADC12.SMM" /></label>
        <label>模式
          <select v-model="mode">
            <option value="FORMAL">FORMAL</option>
            <option value="DEMO">DEMO</option>
          </select>
        </label>
      </div>
      <button :disabled="loading" @click="ask">{{ loading ? '分析中…' : '提交分析' }}</button>
    </section>

    <section v-if="result" class="panel">
      <h2>
        分析结果
        <StatusBadge :status="modeLabel()" />
        <span v-if="result.degraded" class="demo-banner" style="display: inline-block; margin: 0 0 0 8px">
          Java 模板降级：{{ result.degradeReason ?? 'unknown' }}
        </span>
      </h2>
      <p v-if="result.answer" class="answer-text">{{ result.answer }}</p>
      <p class="muted">
        模型/模板来源：{{ result.generatedBy ?? '-' }}
        <template v-if="result.model">（{{ result.model }}）</template>
        ；截至时间：{{ result.dataThrough ?? '-' }}；requestId：{{ result.requestId }}
      </p>

      <div v-if="result.scope" class="scope-row muted">
        范围：{{ result.scope.itemIds.join('、') || '-' }} /
        {{ result.scope.periodStart ?? result.scope.businessDate ?? '-' }} ~
        {{ result.scope.periodEnd ?? '-' }} / {{ result.scope.timezone ?? '-' }}
      </div>

      <h3>Java 事实（facts）</h3>
      <table v-if="result.facts.length > 0" class="sm-table">
        <thead>
          <tr>
            <th>factId</th>
            <th>类型</th>
            <th>值</th>
            <th>业务日期</th>
            <th>校验状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="fact in result.facts" :key="fact.factId">
            <td>{{ fact.factId }}</td>
            <td>{{ fact.statement }}</td>
            <td>{{ fact.value }}</td>
            <td>{{ fact.businessDate ?? '-' }}</td>
            <td><StatusBadge :status="fact.validationStatus ?? 'UNKNOWN'" /></td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">无事实</p>

      <h3>工具调用时间线（只读）</h3>
      <table v-if="result.toolTrace.length > 0" class="sm-table">
        <thead>
          <tr>
            <th>#</th>
            <th>工具</th>
            <th>只读</th>
            <th>状态</th>
            <th>输入</th>
            <th>输出</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="trace in result.toolTrace" :key="trace.invocationIndex">
            <td>{{ trace.invocationIndex }}</td>
            <td>{{ trace.toolName }}@{{ trace.toolVersion }}</td>
            <td>{{ trace.readOnly ? '是' : '否' }}</td>
            <td><StatusBadge :status="trace.status" /></td>
            <td class="cell-truncate">{{ trace.input }}</td>
            <td class="cell-truncate">{{ trace.output }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted">无工具调用</p>

      <h3>报告结论（claims）</h3>
      <ul v-if="result.claims.length > 0" class="claim-list">
        <li v-for="claim in result.claims" :key="claim.claimId">
          {{ claim.text }}
          <span class="muted">[{{ claim.evidenceRefs.length }} 条证据]</span>
        </li>
      </ul>
      <p v-else class="muted">无结论</p>

      <h3>证据引用</h3>
      <ul v-if="result.evidenceRefs.length > 0" class="claim-list">
        <li v-for="ref in result.evidenceRefs" :key="ref">
          <span class="muted">{{ evidenceKind(ref) === 'history' ? '历史' : evidenceKind(ref) === 'warning' ? '预警' : '证据' }}：</span>
          {{ ref }}
        </li>
      </ul>
      <p v-else class="muted">无证据引用</p>

      <h3 v-if="result.limitations.length > 0">限制与说明</h3>
      <ul v-if="result.limitations.length > 0" class="claim-list">
        <li v-for="(limit, index) in result.limitations" :key="index" class="muted">{{ limit }}</li>
      </ul>
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
.form-grid label.wide {
  grid-column: 1 / -1;
}
.form-grid input,
.form-grid select,
.form-grid textarea {
  font-size: 13px;
  padding: 6px 8px;
  border: 1px solid var(--sm-border);
  border-radius: 4px;
  font-family: inherit;
}
button {
  font-size: 13px;
  padding: 6px 12px;
  border: 1px solid var(--sm-border);
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
}
button:hover {
  background: #f0f2f5;
}
.answer-text {
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.scope-row {
  margin: 6px 0 12px;
}
.cell-truncate {
  max-width: 260px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.claim-list {
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.7;
}
</style>
