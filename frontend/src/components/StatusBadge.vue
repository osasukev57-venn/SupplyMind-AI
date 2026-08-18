<script setup lang="ts">
import { computed } from 'vue'
import { jobStatusLabel, modeLabel, stageLabel, validationLabel } from '../lib/labels'

const props = defineProps<{ status: string | null }>()

const tone = computed(() => badgeTone(props.status))
const label = computed(() => badgeLabel(props.status))
</script>

<template>
  <span class="badge" :class="tone">{{ label }}</span>
</template>

<style scoped>
.badge {
  display: inline-block;
  padding: 1px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 550;
  border: 1px solid var(--sm-border-strong);
  color: var(--sm-text-2);
  background: var(--sm-surface-3);
  white-space: nowrap;
}
.badge.ok {
  color: var(--sm-ok);
  border-color: color-mix(in srgb, var(--sm-ok) 45%, transparent);
  background: var(--sm-ok-bg);
}
.badge.warn {
  color: var(--sm-warn);
  border-color: color-mix(in srgb, var(--sm-warn) 45%, transparent);
  background: var(--sm-warn-bg);
}
.badge.info {
  color: var(--sm-accent);
  border-color: color-mix(in srgb, var(--sm-accent) 45%, transparent);
  background: var(--sm-accent-soft);
}
.badge.bad {
  color: var(--sm-bad);
  border-color: color-mix(in srgb, var(--sm-bad) 45%, transparent);
  background: var(--sm-bad-bg);
}
.badge.demo {
  color: var(--sm-demo);
  border-color: color-mix(in srgb, var(--sm-demo) 45%, transparent);
  background: var(--sm-demo-bg);
}
.badge.plain {
  color: var(--sm-muted);
  border-color: var(--sm-border-strong);
  background: var(--sm-surface-3);
}
</style>

<script lang="ts">
/**
 * Status semantics (Industrial Operations Console):
 * - VERIFIED / COMPLETE / SUCCEEDED / FORMAL / ACKNOWLEDGED / ENABLED -> green
 * - VERIFIED_WITH_NOTICE / STALE / PARTIAL_SUCCESS / LOW / MEDIUM -> amber
 * - PENDING / WAITING / AWAITING_MANUAL_INPUT / RUNNING / NO_DATA -> blue/neutral-info
 * - REJECTED / CONFLICT / FAILED / DISABLED / INVALID / CORRUPT / UNAVAILABLE -> red
 * - DEMO -> purple, clearly a demo state
 * - UNKNOWN -> plain gray, NEVER a red failure
 */
export function badgeTone(status: string | null): string {
  if (!status) return 'plain'
  const s = status.toUpperCase()
  if (['VERIFIED', 'COMPLETE', 'SUCCEEDED', 'FORMAL', 'ACKNOWLEDGED', 'ENABLED'].includes(s)) {
    return 'ok'
  }
  if (['VERIFIED_WITH_NOTICE', 'STALE', 'PARTIAL_SUCCESS', 'LOW', 'MEDIUM', 'WARN'].includes(s)) {
    return 'warn'
  }
  if (['PENDING', 'WAITING', 'AWAITING_MANUAL_INPUT', 'RUNNING', 'NO_DATA', 'NOT_TRIGGERED'].includes(s)) {
    return 'info'
  }
  if (['REJECTED', 'CONFLICT', 'FAILED', 'DISABLED', 'INVALID', 'CORRUPT', 'UNAVAILABLE', 'HIGH', 'BLOCKED'].includes(s)) {
    return 'bad'
  }
  if (s === 'DEMO' || s.startsWith('DEMO_')) {
    return 'demo'
  }
  return 'plain'
}

/** Friendly label for known statuses; unknown values stay verbatim. */
export function badgeLabel(status: string | null): string {
  if (!status) return '未知'
  const jobLabel = jobStatusLabel(status)
  if (jobLabel !== status && jobLabel !== '—') return jobLabel
  const modeLabel2 = modeLabel(status)
  if (modeLabel2 !== status && modeLabel2 !== '—') return modeLabel2
  const stageLabel2 = stageLabel(status)
  if (stageLabel2 !== status && stageLabel2 !== '—') return stageLabel2
  const validationLabel2 = validationLabel(status)
  if (validationLabel2 !== status && validationLabel2 !== '—') return validationLabel2
  const map: Record<string, string> = {
    FORMAL: '正式运行',
    DEMO: '演示模式',
    UNKNOWN: '未知',
    NO_DATA: '暂无数据',
    STALE: '已过期',
    NOT_TRIGGERED: '未触发',
    ENABLED: '已启用',
    DISABLED: '已停用',
    ACKNOWLEDGED: '已确认',
    TRIGGERED: '已触发'
  }
  return map[status] ?? status
}
</script>
