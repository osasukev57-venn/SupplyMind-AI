<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import { queryAgent } from '../api/agent'
import type { AgentQueryResponse, EvidenceLinkView } from '../types/agent'
import StatusBadge from '../components/StatusBadge.vue'

/**
 * Agent workbench: questions are answered through verified read-only analysis tools and
 * evidence-backed reports. The page only renders what the backend returns - it never
 * computes risk, thresholds, recommendations or values (DEC-008 preserved).
 */

const question = ref('')
const itemId = ref('')
const mode = ref('FORMAL')
const loading = ref(false)
const errorMessage = ref<string | null>(null)
const result = ref<AgentQueryResponse | null>(null)

async function ask() {
  if (loading.value) return
  if (!question.value.trim()) {
    errorMessage.value = '请输入问题'
    return
  }
  loading.value = true
  errorMessage.value = null
  result.value = null
  try {
    const response = await queryAgent({
      question: question.value.trim(),
      itemId: itemId.value || undefined,
      mode: mode.value
    })
    if (!response) {
      errorMessage.value = '服务暂时不可用，请稍后重试'
      return
    }
    result.value = response
  } catch (error) {
    errorMessage.value = error instanceof Error && error.message
      ? error.message
      : '服务暂时不可用，请稍后重试'
  } finally {
    loading.value = false
  }
}

function sourceLabel() {
  if (!result.value) return '—'
  if (result.value.degraded) return 'Java 事实报告'
  return result.value.generatedBy === 'LLM' ? '云端智能分析（Java 事实约束）' : (result.value.generatedBy ?? '—')
}

function reportStatus(): string {
  if (!result.value) return 'UNKNOWN'
  return result.value.degraded ? 'JAVA_FACT_REPORT' : 'VERIFIED'
}

function linkTarget(link: EvidenceLinkView): string {
  return link.query ? `${link.route}?${link.query}` : link.route
}

function evidenceKind(link: EvidenceLinkView): string {
  return link.targetView === 'HISTORY' ? '历史' : link.targetView === 'WARNING' ? '预警' : '质量'
}
</script>

<template>
  <div>
    <header class="page-head">
      <div class="page-eyebrow">运营分析</div>
      <h1 class="page-title">Agent 工作台</h1>
      <p class="page-desc">Java 确定性工具链先生成并校验标准事实，云端模型仅在事实约束内组织分析语言；所有结论都可追溯到正式证据。</p>
    </header>

    <div v-if="errorMessage" class="error-banner">{{ errorMessage }}</div>

    <section class="section">
      <div class="section-head">
        <h2>提问</h2>
      </div>
      <div class="section-body">
        <div class="form-grid">
          <label style="grid-column: 1 / -1">问题
            <textarea v-model="question" rows="3" placeholder="例如：分析 ADC12 近期价格走势" />
          </label>
          <label>监测项编号（可选）
            <input v-model="itemId" placeholder="如 MAT.ADC12.SMM" />
          </label>
          <label>运行模式
            <select v-model="mode">
              <option value="FORMAL">正式运行</option>
              <option value="DEMO">演示模式</option>
            </select>
          </label>
        </div>
        <div class="form-actions">
          <button class="btn-primary" :disabled="loading" @click="ask">
            {{ loading ? '分析中…' : '提交分析' }}
          </button>
        </div>
      </div>
    </section>

    <section v-if="result" class="section">
      <div class="section-head">
        <h2>分析结果</h2>
        <span>
          <StatusBadge :status="reportStatus()" />
        </span>
      </div>
      <div class="section-body">
        <div v-if="result.degraded" class="demo-banner">
          云端语言分析未被采用，当前展示经过证据约束的 Java 事实报告{{ result.degradeReason ? '（' + result.degradeReason + '）' : '' }}
        </div>
        <div v-else class="fact-banner">Java 标准事实校验通过，云端模型仅负责组织以下分析表达。</div>
        <p class="answer-text">{{ result.answer }}</p>
        <p class="muted">
          报告来源：{{ sourceLabel() }}
          <template v-if="result.model">（{{ result.model }}）</template>
          ；数据截至：{{ result.dataThrough ?? '—' }}；请求号：{{ result.requestId }}
        </p>
        <p class="muted" v-if="result.scope">
          范围：{{ result.scope.itemIds.join('、') || '—' }} /
          {{ result.scope.periodStart ?? result.scope.businessDate ?? '—' }} ~ {{ result.scope.periodEnd ?? '—' }}
        </p>
      </div>

      <div class="section-body flat" v-if="result.risk">
        <div v-if="result.risk.demoRule" class="demo-banner">
          当前风险结果来自演示规则，仅用于验证分析流程，不代表正式业务阈值。
        </div>
        <table class="sm-table">
          <thead>
            <tr>
              <th>风险等级</th>
              <th>当前值</th>
              <th>基准值</th>
              <th>阈值</th>
              <th>数据状态</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td><StatusBadge :status="result.risk.riskLevel ?? 'UNKNOWN'" /></td>
              <td class="num">{{ result.risk.currentValue }}</td>
              <td class="num">{{ result.risk.baselineValue ?? '—' }}</td>
              <td class="num">{{ result.risk.threshold ?? '—' }}</td>
              <td>{{ result.risk.dataStatus ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="section-body" v-if="result.facts.length > 0">
        <h3>事实数据</h3>
        <div class="table-scroll">
          <table class="sm-table">
            <thead>
              <tr>
                <th>类型</th>
                <th>值</th>
                <th>业务日期</th>
                <th>校验状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="fact in result.facts" :key="fact.factId">
                <td>{{ fact.statement }}</td>
                <td class="num">{{ fact.value }}</td>
                <td class="num">{{ fact.businessDate ?? '—' }}</td>
                <td><StatusBadge :status="fact.validationStatus ?? 'UNKNOWN'" /></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="section-body" v-if="result.toolTrace.length > 0">
        <h3>分析步骤</h3>
        <div class="table-scroll">
          <table class="sm-table">
            <thead>
              <tr>
                <th>#</th>
                <th>步骤</th>
                <th>状态</th>
                <th>输入</th>
                <th>输出</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="trace in result.toolTrace" :key="trace.invocationIndex">
                <td class="num">{{ trace.invocationIndex }}</td>
                <td>{{ trace.toolName }}</td>
                <td><StatusBadge :status="trace.status" /></td>
                <td class="cell-truncate">{{ trace.input }}</td>
                <td class="cell-truncate">{{ trace.output }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="section-body" v-if="result.claims.length > 0">
        <h3>结论</h3>
        <ul class="claim-list">
          <li v-for="claim in result.claims" :key="claim.claimId">
            {{ claim.text }}
            <span class="muted">[{{ claim.evidenceRefs.length }} 条证据]</span>
          </li>
        </ul>
      </div>

      <div class="section-body" v-if="result.recommendations.length > 0">
        <h3>建议</h3>
        <ul class="claim-list">
          <li v-for="(recommendation, index) in result.recommendations" :key="index">
            {{ recommendation }}
          </li>
        </ul>
      </div>

      <div class="section-body" v-if="result.evidenceLinks.length > 0">
        <h3>证据来源（可点击查看）</h3>
        <ul class="claim-list">
          <li v-for="link in result.evidenceLinks" :key="link.evidenceId">
            <RouterLink :to="linkTarget(link)" class="evidence-link">
              {{ evidenceKind(link) }}：{{ link.evidenceType }}（{{ link.itemId }}）
            </RouterLink>
            <span v-if="link.businessDate || link.periodStart" class="muted">
              [{{ link.businessDate ?? link.periodStart }} {{ link.grain ?? '' }}]
            </span>
          </li>
        </ul>
      </div>

      <div class="section-body" v-if="result.calculationBasis">
        <h3>计算口径</h3>
        <table class="sm-table">
          <tbody>
            <tr>
              <td>校验版本</td>
              <td>{{ result.calculationBasis.validationVersion ?? '—' }}</td>
            </tr>
            <tr>
              <td>计算版本</td>
              <td>{{ result.calculationBasis.calculationVersion ?? '—' }}</td>
            </tr>
            <tr>
              <td>日历版本</td>
              <td>{{ result.calculationBasis.calendarVersion ?? '—' }}</td>
            </tr>
            <tr>
              <td>配置版本</td>
              <td class="num">{{ result.calculationBasis.configVersions.join('、') || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="section-body" v-if="result.limitations.length > 0">
        <h3>说明</h3>
        <ul class="claim-list">
          <li v-for="(limit, index) in result.limitations" :key="index" class="muted">{{ limit }}</li>
        </ul>
      </div>
    </section>
  </div>
</template>

<style scoped>
.answer-text {
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
}
.claim-list {
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.7;
  margin: 6px 0;
}
.evidence-link {
  color: var(--sm-accent);
  text-decoration: none;
}
.evidence-link:hover {
  text-decoration: underline;
}
h3 {
  margin: 0 0 8px;
  font-size: 13.5px;
  font-weight: 650;
  color: var(--sm-text-2);
}
</style>
