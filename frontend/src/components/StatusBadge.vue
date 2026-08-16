<script setup lang="ts">
defineProps<{ status: string | null }>()
</script>

<template>
  <span class="badge" :class="badgeClass(status)">{{ status ?? 'UNKNOWN' }}</span>
</template>

<style scoped>
.badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 12px;
  border: 1px solid var(--sm-border);
  color: var(--sm-muted);
}
.badge.ok {
  color: var(--sm-ok);
  border-color: var(--sm-ok);
  background: #e8f5e9;
}
.badge.warn {
  color: var(--sm-warn);
  border-color: var(--sm-warn);
  background: #fff8e1;
}
.badge.bad {
  color: var(--sm-bad);
  border-color: var(--sm-bad);
  background: #fdecea;
}
</style>

<script lang="ts">
export function badgeClass(status: string | null): string {
  if (!status) return ''
  if (status === 'VERIFIED' || status === 'COMPLETE') return 'ok'
  if (status === 'NO_DATA' || status === 'PENDING') return 'warn'
  return 'bad'
}
</script>
