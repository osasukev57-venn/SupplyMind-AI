<script setup lang="ts">
import { computed, ref, onBeforeUnmount, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { queryAgent, readSavedReport } from '../api/agent'
import { fetchConfigItems } from '../api/config'
import type { ItemView } from '../types/config'
import type { AgentQueryResponse, EvidenceLinkView, SavedReportView } from '../types/agent'
import StatusBadge from '../components/StatusBadge.vue'

/**
 * Agent workbench: questions are answered through verified read-only analysis tools and
 * evidence-backed reports. The page only renders what the backend returns - it never
 * computes risk, thresholds, recommendations or values (DEC-008 preserved).
 */

let pendingRequest: AbortController | undefined
onBeforeUnmount(() => pendingRequest?.abort())

const availableItems = ref<ItemView[]>([])
onMounted(async () => { const config = await fetchConfigItems(); availableItems.value = config?.items.filter(i => i.enabled) ?? [] })
const startDate = ref('')
const endDate = ref('')
const theme = ref('COMPREHENSIVE')
const savedRef = ref('')
const savedReport = ref<SavedReportView | null>(null)
const readingSaved = ref(false)
async function openSaved() {
  if (!savedRef.value.trim() || readingSaved.value) return
  readingSaved.value = true
  try { savedReport.value = await readSavedReport(savedRef.value.trim()); errorMessage.value = null }
  catch (error) { savedReport.value = null; errorMessage.value = error instanceof Error ? error.message : '报告不可验证' }
  finally { readingSaved.value = false }
}
function cloudAvailability(status: string) {
  if (status === 'not_configured') return '尚未配置云模型'
  if (status === 'ANALYSIS_CONTEXT_LIMIT') return '模型上下文范围过大，已保留本地事实；请缩小日期或统计组范围'
  if (status.includes('BUDGET') || status.includes('TIMEOUT')) return '云端分析超时'
  if (status.includes('CAPACITY')) return '分析服务忙，请稍后重试'
  return '本次云端解读不可用或未通过检查'
}
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
  if (!itemId.value.trim()) {
    errorMessage.value = '请选择或填写监测项编号'
    return
  }
  pendingRequest = new AbortController()
  loading.value = true
  errorMessage.value = null
  result.value = null
  try {
    const response = await queryAgent({
      question: question.value.trim(),
      itemId: itemId.value.trim(),
      mode: mode.value,
      theme: theme.value,
      ...(startDate.value ? { startDate: startDate.value } : {}),
      ...(endDate.value ? { endDate: endDate.value } : {})
    }, pendingRequest.signal)
    if (!response) {
      errorMessage.value = '服务暂时不可用，请稍后重试'
      return
    }
    result.value = response
    savedRef.value = response.reportRef ?? ''
  } catch (error) {
    errorMessage.value = error instanceof Error && error.message
      ? error.message
      : '服务暂时不可用，请稍后重试'
  } finally {
    pendingRequest = undefined
    loading.value = false
  }
}

function sourceLabel() {
  if (!result.value) return '—'
  if (result.value.narrative?.adoption === 'PARTIAL') return '云端智能解读（部分采用）+ Java 事实'
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

const visibleClaims = computed(() => {
  if (!result.value) return []
  const answer = result.value.answer?.trim()
  return result.value.claims.filter((claim) => claim.text.trim() !== answer)
})
</script>

<template>
  <div>
    <header class="page-head">
      <div class="page-eyebrow">运营分析</div>
      <h1 class="page-title">Agent 工作台</h1>
      <p class="page-desc">Java 确定性工具链先生成并校验标准事实，云端模型仅在事实约束内组织分析语言；事实依据可追溯；云端解读与观察建议独立呈现，不代替业务判断。</p>
    </header>

    <div v-if="errorMessage" class="error-banner">{{ errorMessage }}</div>

    <section class="section">
      <div class="section-head">
        <h2>提问</h2>
      </div>
      <div class="section-body">
        <div class="form-grid">
          <label style="grid-column: 1 / -1">问题
            <textarea v-model="question" rows="3" placeholder="例如：分析模拟 AZ91D 的走势、月度均值与预警确认" />
          </label>
          <label>监测项（搜索或选择） <span class="required-mark">*</span>
            <input list="agent-items" v-model="itemId" required placeholder="选择监测项，可搜索编号" />
            <datalist id="agent-items"><option v-for="item in availableItems" :key="item.itemId" :value="item.itemId">{{ item.displayName }}</option></datalist>
          </label>
          <label>运行模式
            <select v-model="mode"><option value="FORMAL">正式运行</option><option value="DEMO">演示模式</option></select>
          </label>
          <label>开始日期（可选）<input v-model="startDate" type="date" aria-label="开始日期" /></label>
          <label>结束日期（可选）<input v-model="endDate" type="date" aria-label="结束日期" /></label>
          <label>分析主题<select v-model="theme" aria-label="分析主题"><option value="COMPREHENSIVE">综合分析</option><option value="HISTORY">历史趋势</option><option value="METRICS">均值统计</option><option value="WARNING">预警与确认</option></select></label>
        </div>
        <p class="muted">留空日期时由后端按该项最新可用业务日期确定范围；与问题日期冲突时请核对。</p>
        <div class="form-actions">
          <button class="btn-primary" :disabled="loading" @click="ask">
            {{ loading ? '分析中…' : '提交分析' }}
          </button>
        </div>
      </div>
    </section>

    <section class="section">
      <div class="section-head"><h2>读取已保存报告</h2></div>
      <div class="section-body"><label>报告标识<input v-model="savedRef" aria-label="报告标识" placeholder="粘贴上次保存的报告标识" /></label><button class="btn-secondary" :disabled="readingSaved" @click="openSaved">{{ readingSaved ? '核验中…' : '核验并打开报告' }}</button><p class="muted">读取时重新核验证据，显示生成时事实；不调用模型，不替换为最新数据。</p>
      <div v-if="savedReport"><h3>生成时报告 · {{ savedReport.requestId }}</h3><p>{{ savedReport.createdAt }} · {{ savedReport.generatedBy === 'JAVA_TEMPLATE' ? 'Java 事实报告' : savedReport.generatedBy === 'LLM_PARTIAL' ? '云端部分采用' : '云端解读' }}<template v-if="savedReport.narrative"> · 云端采用状态 {{ savedReport.narrative.adoption === 'FULL' ? '全部采用' : savedReport.narrative.adoption === 'PARTIAL' ? '部分采用' : '未采用' }}</template></p><p>{{ savedReport.scope.periodStart }} 至 {{ savedReport.scope.periodEnd }}</p><p class="answer-text">{{ savedReport.javaSummary }}</p><article v-for="s in savedReport.narrative?.sections ?? []" :key="s.sectionId"><p>{{ s.text }}</p></article></div></div>
    </section>
    <section v-if="result" class="section">
      <div class="section-head">
        <h2>分析结果</h2>
        <span>
          <StatusBadge :status="reportStatus()" />
        </span>
      </div>
      <div class="section-body">
        <div v-if="result.degraded && result.narrative?.adoption !== 'PARTIAL'" class="demo-banner">
          云端语言分析未被采用，当前展示经过证据约束的 Java 事实报告{{ result.degradeReason ? '（' + result.degradeReason + '）' : '' }}
        </div>
        <div v-else class="fact-banner">事实依据已由 Java 核验；智能解读经过结构、引用和关键主张检查，不等于任意语义均已证明。</div>
        <details><summary>完整生成时 Java 事实报告</summary><p class="answer-text">{{ result.answer }}</p></details>
        <p class="muted">
          报告来源：{{ sourceLabel() }} · 生成时事实（不是实时数据）
          <template v-if="result.model">（{{ result.model }}）</template>
          ；数据截至：{{ result.dataThrough ?? '—' }}；请求号：{{ result.requestId }}；报告标识：{{ result.reportRef ?? '未保存' }}
        </p>
        <p class="muted" v-if="result.scope">
          范围：{{ result.scope.itemIds.join('、') || '—' }} /
          {{ result.scope.periodStart ?? result.scope.businessDate ?? '—' }} ~ {{ result.scope.periodEnd ?? result.scope.businessDate ?? '—' }}
        </p>
      </div>

      <div v-if="result.simulation" class="demo-banner">模拟演示数据，不代表真实市场行情或真实市场风险。确认不代表风险消失。</div>
      <div class="section-body fact-cards">
        <h3>Java 已验证事实</h3>
        <article v-for="fact in result.facts.filter(f => ['history.latest','history.trend.direction','period.metrics.avg'].includes(f.statement))" :key="fact.factId" class="fact-card">
          <strong>{{ fact.statement === 'history.latest' ? '最新值' : fact.statement === 'history.trend.direction' ? '区间趋势' : '持久化均值' }}</strong>
          <p>{{ fact.value === 'UPWARD' ? '上涨' : fact.value === 'DOWNWARD' ? '下跌' : fact.value === 'FLAT' ? '持平' : fact.value }} {{ fact.unit }}</p>
          <p class="muted">{{ fact.periodStart ?? fact.businessDate }}<template v-if="fact.periodEnd"> 至 {{ fact.periodEnd }}</template> · {{ fact.validationStatus }}</p>
          <p v-if="fact.statement === 'period.metrics.avg'" class="muted">该组样本截至 {{ fact.businessDate ?? '不可验证' }}</p>
          <p v-if="fact.statement === 'period.metrics.avg' && fact.qualityStatus === 'false'" class="muted">该周期数据未完整，均值仅代表已发布样本。</p>
          <p v-if="fact.rowEvidenceId" class="muted">规格 {{ fact.canonicalSpecCode ?? '不适用' }} · {{ fact.actualSourceName }} · 配置版本 {{ fact.configVersions?.join('、') }}</p>
        </article>
      </div>
      <div class="section-body" v-if="result.narrative">
        <p class="muted">模型阶段：{{ result.narrative.cloudStatus === 'not_configured' || result.narrative.attempts === 0 ? '本次未发起云端请求' : result.llmStatus === 'SUCCESS' ? '已收到模型响应' : '模型阶段未成功完成' }}；{{ result.model ?? '未配置模型' }}。采用状态独立于事实校验。</p>
        <h3>云端智能解读 · {{ result.narrative.adoption === 'FULL' ? '全部采用' : result.narrative.adoption === 'PARTIAL' ? '部分采用' : '未采用' }}</h3>
        <p v-if="result.narrative.adoption === 'PARTIAL'" class="muted">部分段落未通过检查，以下独立合格段落已保留；Java 事实保持可用。</p>
        <p v-if="result.narrative.adoption === 'NONE'" class="muted">云端解读不可用或未通过检查（{{ cloudAvailability(result.narrative.cloudStatus) }}）；本次使用本地 Java 事实报告。</p>
        <article v-for="section in result.narrative.sections" :key="section.sectionId" class="narrative-section">
          <h3>{{ ({ OVERVIEW: '概览', TREND: '趋势解读', METRICS: '均值解读', WARNING: '预警与确认', QUALITY: '数据质量' } as Record<string,string>)[section.kind] }}</h3>
          <p class="answer-text">{{ section.text }}</p>
          <p class="muted">依据：<a v-for="id in section.factIds" :key="id" :href="'#' + id">{{ id }} </a></p>
        </article>
        <div v-if="result.narrative.recommendations.length"><h3>观察建议（模型建议）</h3><p v-for="r in result.narrative.recommendations" :key="r.sectionId">{{ r.text }}</p></div>
        <details><summary>云端检查状态</summary><p>{{ result.narrative.checkScope }}</p><ul><li v-for="d in result.narrative.diagnostics" :key="d.sectionId">{{ d.sectionId }}：{{ d.code }}</li></ul></details>
      </div>
      <p v-if="!result.risk && result.currentWarningStatus" class="section-body muted">当前范围和有效规则暂无可验证的适用预警记录，不代表风险为零。</p>
      <div class="section-body flat" v-if="result.risk">
        <p class="muted">适用预警：{{ result.risk.warningId ?? '—' }}；规则 {{ result.risk.ruleId ?? '—' }} / {{ result.risk.ruleVersion ?? '—' }}；比较日期 {{ result.risk.periodStart ?? '—' }} 至 {{ result.risk.periodEnd ?? '—' }}；处理状态 {{ result.risk.acknowledgedStatus === 'ACKNOWLEDGED' ? '已确认（确认不代表风险消失）' : result.risk.acknowledgedStatus === 'PENDING' ? '待处理' : '确认状态不可验证' }}</p>
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

      <details class="section-body audit-details" v-if="result.facts.length > 0">
        <summary>查看事实数据（{{ result.facts.length }} 条）</summary>
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
              <tr v-for="fact in result.facts" :key="fact.factId" :id="fact.factId">
                <td>{{ fact.statement }}</td>
                <td class="num">{{ fact.value }}</td>
                <td class="num">{{ fact.businessDate ?? '—' }}</td>
                <td><span v-if="fact.validationStatus == null && fact.statement.startsWith('warning.')">不适用（预警证据单独核验）</span><StatusBadge v-else :status="fact.validationStatus ?? 'UNKNOWN'" /></td>
              </tr>
            </tbody>
          </table>
        </div>
      </details>

      <details class="section-body audit-details" v-if="result.toolTrace.length > 0">
        <summary>查看分析步骤（{{ result.toolTrace.length }} 步）</summary>
        <p class="muted">详细输入输出保存在可追溯报告中，工作台仅展示步骤状态，避免内部结构化数据干扰阅读。</p>
        <div class="table-scroll">
          <table class="sm-table">
            <thead>
              <tr>
                <th>#</th>
                <th>步骤</th>
                <th>状态</th>
                <th>证据数</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="trace in result.toolTrace" :key="trace.invocationIndex">
                <td class="num">{{ trace.invocationIndex }}</td>
                <td>{{ trace.toolName }}</td>
                <td><StatusBadge :status="trace.status" /></td>
                <td class="num">{{ trace.evidenceRefs.length }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </details>

      <div class="section-body" v-if="visibleClaims.length > 0">
        <h3>结论</h3>
        <ul class="claim-list">
          <li v-for="claim in visibleClaims" :key="claim.claimId">
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
.fact-cards { display: flex; flex-wrap: wrap; gap: 14px; }
.fact-cards h3 { width: 100%; }
.fact-card { flex: 1 1 230px; border: 1px solid var(--sm-border); padding: 14px; border-radius: 8px; }
.narrative-section { margin: 16px 0; }
.answer-text {
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
}
.required-mark {
  color: var(--sm-danger);
}
.audit-details > summary {
  cursor: pointer;
  font-weight: 650;
  color: var(--sm-text-2);
}
.audit-details[open] > summary {
  margin-bottom: 12px;
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
