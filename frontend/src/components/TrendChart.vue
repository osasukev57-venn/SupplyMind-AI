<script setup lang="ts">
import type { Chart } from '../types/dashboard'

/**
 * D7 render-only trend chart. The BACKEND computes every display coordinate (fixed size,
 * min/max scaling) and the Vue layer ONLY renders them: this component performs no numeric
 * computation of any kind. label carries the original businessDate + value string straight
 * from the backend.
 */
const props = defineProps<{ chart: Chart }>()

function polylinePoints(): string {
  return props.chart.points.map((p) => p.x + ',' + p.y).join(' ')
}
</script>

<template>
  <svg :width="chart.width" :height="chart.height"
    :viewBox="`0 0 ${chart.width} ${chart.height}`" role="img" aria-label="历史趋势图">
    <polyline v-if="polylinePoints()" :points="polylinePoints()" fill="none"
      stroke="#1565c0" stroke-width="1.5" />
    <text v-if="chart.points.length > 0" x="8" y="12" font-size="10" fill="#646a73">
      {{ chart.points[0].label }}
    </text>
  </svg>
</template>
