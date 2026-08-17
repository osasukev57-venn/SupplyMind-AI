<script setup lang="ts">
defineProps<{ status: string | null }>()
</script>

<template>
  <span class="badge" :class="badgeClass(status)">{{ status ?? 'UNKNOWN' }}</span>
</template>

<style scoped>
.badge {
  display: inline-block;
  padding: 1px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 550;
  border: 1px solid var(--sm-border-strong);
  color: var(--sm-text-secondary);
  background: var(--sm-surface-sunken);
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
.badge.bad {
  color: var(--sm-bad);
  border-color: color-mix(in srgb, var(--sm-bad) 45%, transparent);
  background: var(--sm-bad-bg);
}
</style>

<script lang="ts">
export function badgeClass(status: string | null): string {
  if (!status) return ''
  if (
    status === 'VERIFIED' ||
    status === 'VERIFIED_WITH_NOTICE' ||
    status === 'COMPLETE' ||
    status === 'SUCCEEDED' ||
    status === 'ACKNOWLEDGED' ||
    status === 'ENABLED' ||
    status === 'FORMAL'
  ) {
    return 'ok'
  }
  if (
    status === 'NO_DATA' ||
    status === 'PENDING' ||
    status === 'WAITING' ||
    status === 'AWAITING_MANUAL_INPUT' ||
    status === 'PARTIAL_SUCCESS' ||
    status === 'RUNNING' ||
    status === 'DEMO' ||
    status === 'LOW' ||
    status === 'MEDIUM'
  ) {
    return 'warn'
  }
  return 'bad'
}
</script>
