<script setup lang="ts">
import { computed } from 'vue'

/**
 * D7 SVG trend line - renders ONLY the points the backend returned. It never computes
 * averages, trends or missing data; missing dates are simply absent (never interpolated).
 * The displayed value text is the backend's original string.
 */
const props = defineProps<{
  points: { businessDate: string; value: string }[]
  width?: number
  height?: number
}>()

const W = computed(() => props.width ?? 640)
const H = computed(() => props.height ?? 160)

const geometry = computed(() => {
  const points = props.points
  if (points.length === 0) return { path: '', firstLabel: '' }
  const values = points.map((p) => Number(p.value))
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  const pad = 8
  const usable = W.value - pad * 2
  const step = points.length === 1 ? 0 : usable / (points.length - 1)
  const coords = points.map((p, index) => {
    const x = pad + (points.length === 1 ? usable / 2 : index * step)
    const y = H.value - pad - ((Number(p.value) - min) / span) * (H.value - pad * 2)
    return { x, y, ...p }
  })
  const path = coords
    .map((c, i) => `${i === 0 ? 'M' : 'L'} ${c.x.toFixed(1)} ${c.y.toFixed(1)}`)
    .join(' ')
  return { path, firstLabel: `${coords[0].businessDate} ${coords[0].value}` }
})

function dotPoints(): string {
  const points = props.points
  if (points.length === 0) return ''
  const values = points.map((p) => Number(p.value))
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  const pad = 8
  const usable = W.value - pad * 2
  const step = points.length === 1 ? 0 : usable / (points.length - 1)
  return points
    .map((p, index) => {
      const x = pad + (points.length === 1 ? usable / 2 : index * step)
      const y = H.value - pad - ((Number(p.value) - min) / span) * (H.value - pad * 2)
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
}
</script>

<template>
  <svg :width="W" :height="H" :viewBox="`0 0 ${W} ${H}`" role="img" aria-label="历史趋势图">
    <polyline v-if="geometry.path" :points="geometry.path" fill="none" stroke="#1565c0"
      stroke-width="1.5" />
    <polyline v-if="dotPoints()" :points="dotPoints()" fill="none" stroke="none" />
    <text x="8" y="12" font-size="10" fill="#646a73">{{ geometry.firstLabel }}</text>
  </svg>
</template>
