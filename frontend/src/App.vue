<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="app">
    <header class="app-header">
      <span class="app-title">
        <svg class="app-mark" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
          <path d="M3 17h4v4H3zM10 9h4v12h-4zM17 3h4v18h-4z" fill="currentColor" />
        </svg>
        SupplyMind 监测仪表盘
      </span>
      <nav class="app-nav">
        <RouterLink to="/dashboard">总览</RouterLink>
        <RouterLink to="/history">历史趋势</RouterLink>
        <RouterLink to="/quality">数据质量</RouterLink>
        <RouterLink to="/sources">来源与录入</RouterLink>
        <RouterLink to="/config">动态配置</RouterLink>
        <RouterLink to="/warning">预警</RouterLink>
        <RouterLink to="/agent">Agent</RouterLink>
      </nav>
    </header>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>

<style>
/* ------------------------------------------------------------------
   SupplyMind design tokens - single neutral (zinc) base, one accent.
   Light + dark via prefers-color-scheme. Radius scale locked: 8px.
------------------------------------------------------------------- */
:root {
  --sm-border: #d8dce1;
  --sm-border-strong: #c2c8d0;
  --sm-text: #1c2128;
  --sm-text-secondary: #3f4651;
  --sm-muted: #67707d;
  --sm-bg: #f4f6f8;
  --sm-surface: #ffffff;
  --sm-surface-raised: #ffffff;
  --sm-surface-sunken: #eef1f4;
  --sm-ok: #1e7d45;
  --sm-ok-bg: #e6f4ec;
  --sm-warn: #a05e00;
  --sm-warn-bg: #fdf1dd;
  --sm-bad: #bf2c33;
  --sm-bad-bg: #fdeaea;
  --sm-accent: #0f62a8;
  --sm-accent-strong: #0b4f87;
  --sm-accent-soft: #e3eef9;
  --sm-focus: #0f62a8;
  --sm-radius: 8px;
  --sm-radius-sm: 6px;
  --sm-shadow: 0 1px 2px rgba(20, 26, 34, 0.05), 0 1px 6px rgba(20, 26, 34, 0.04);
  --sm-font-mono: 'Cascadia Mono', 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

@media (prefers-color-scheme: dark) {
  :root {
    --sm-border: #343b46;
    --sm-border-strong: #454f5d;
    --sm-text: #e6e9ee;
    --sm-text-secondary: #c2c9d3;
    --sm-muted: #98a1ad;
    --sm-bg: #151a21;
    --sm-surface: #1c222b;
    --sm-surface-raised: #212933;
    --sm-surface-sunken: #171d25;
    --sm-ok: #4cbf82;
    --sm-ok-bg: #143124;
    --sm-warn: #e0a34a;
    --sm-warn-bg: #332715;
    --sm-bad: #e06a70;
    --sm-bad-bg: #382026;
    --sm-accent: #5ba6e6;
    --sm-accent-strong: #7cb8ec;
    --sm-accent-soft: #17293a;
    --sm-focus: #5ba6e6;
    --sm-shadow: 0 1px 2px rgba(0, 0, 0, 0.3), 0 1px 8px rgba(0, 0, 0, 0.25);
  }
}

* {
  box-sizing: border-box;
}

html {
  -webkit-text-size-adjust: 100%;
}

body {
  margin: 0;
  font-family: 'Segoe UI', 'Microsoft YaHei', system-ui, -apple-system, sans-serif;
  font-size: 14px;
  line-height: 1.5;
  color: var(--sm-text);
  background: var(--sm-bg);
}

/* ---------- header / navigation ---------- */
.app-header {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 10px 24px;
  background: #18222e;
  color: #fff;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  position: sticky;
  top: 0;
  z-index: 20;
}

@media (prefers-color-scheme: dark) {
  .app-header {
    background: #10151c;
  }
}

.app-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.01em;
  white-space: nowrap;
}

.app-mark {
  color: var(--sm-accent);
}

.app-nav {
  display: flex;
  align-items: center;
  gap: 2px;
  overflow-x: auto;
}

.app-nav a {
  color: #b9c4d0;
  text-decoration: none;
  padding: 6px 10px;
  font-size: 13.5px;
  border-radius: var(--sm-radius-sm);
  white-space: nowrap;
  transition: color 0.15s ease, background-color 0.15s ease;
}

.app-nav a:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
}

.app-nav a.router-link-active {
  color: #fff;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.12);
}

.app-nav a:focus-visible {
  outline: 2px solid var(--sm-focus);
  outline-offset: 1px;
}

/* ---------- page frame ---------- */
.app-main {
  padding: 24px;
  max-width: 1240px;
  margin: 0 auto;
}

.app-main h1 {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

@media (max-width: 768px) {
  .app-main {
    padding: 14px;
  }
  .app-header {
    padding: 8px 14px;
    flex-wrap: wrap;
    gap: 8px;
  }
  .app-title {
    font-size: 14px;
  }
}

/* ---------- panels ---------- */
.panel {
  background: var(--sm-surface);
  border: 1px solid var(--sm-border);
  border-radius: var(--sm-radius);
  padding: 18px;
  margin-bottom: 16px;
  box-shadow: var(--sm-shadow);
}

.panel h2 {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 650;
  color: var(--sm-text);
}

.panel h3 {
  margin: 20px 0 8px;
  font-size: 13.5px;
  font-weight: 650;
  color: var(--sm-text-secondary);
}

.muted {
  color: var(--sm-muted);
  font-size: 13px;
}

/* ---------- tables ---------- */
.table-scroll {
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
}

table.sm-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 13px;
  background: var(--sm-surface);
}

table.sm-table th,
table.sm-table td {
  border-bottom: 1px solid var(--sm-border);
  border-right: 1px solid var(--sm-border);
  padding: 8px 10px;
  text-align: left;
  word-break: break-all;
  vertical-align: top;
}

table.sm-table th:last-child,
table.sm-table td:last-child {
  border-right: none;
}

table.sm-table th {
  background: var(--sm-surface-sunken);
  font-weight: 600;
  color: var(--sm-text-secondary);
  font-size: 12.5px;
  white-space: nowrap;
  position: sticky;
  top: 0;
  z-index: 1;
}

table.sm-table tbody tr:hover {
  background: var(--sm-accent-soft);
}

table.sm-table tbody tr:nth-child(even) {
  background: color-mix(in srgb, var(--sm-surface) 96%, var(--sm-surface-sunken));
}

table.sm-table tbody tr:hover:nth-child(even) {
  background: var(--sm-accent-soft);
}

/* precise decimal strings render in mono, never re-computed in the browser */
table.sm-table .num {
  font-family: var(--sm-font-mono);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* responsive tables: panels containing sm-table scroll horizontally on narrow screens */
.panel:has(> table.sm-table) {
  overflow-x: auto;
}

@media (max-width: 768px) {
  table.sm-table th,
  table.sm-table td {
    white-space: nowrap;
  }
}

/* ---------- banners ---------- */
.error-banner {
  border: 1px solid var(--sm-bad);
  background: var(--sm-bad-bg);
  color: var(--sm-bad);
  padding: 10px 12px;
  border-radius: var(--sm-radius-sm);
  margin-bottom: 12px;
  font-size: 13px;
}

.demo-banner {
  border: 1px solid var(--sm-warn);
  background: var(--sm-warn-bg);
  color: var(--sm-warn);
  padding: 8px 12px;
  border-radius: var(--sm-radius-sm);
  margin-bottom: 12px;
  font-size: 13px;
  font-weight: 600;
}

/* ---------- forms ---------- */
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.form-grid label {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12.5px;
  color: var(--sm-muted);
}

.form-grid input,
.form-grid select,
.form-grid textarea {
  font-family: inherit;
  font-size: 13.5px;
  padding: 7px 9px;
  border: 1px solid var(--sm-border-strong);
  border-radius: var(--sm-radius-sm);
  background: var(--sm-surface);
  color: var(--sm-text);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.form-grid input::placeholder,
.form-grid textarea::placeholder {
  color: var(--sm-muted);
}

.form-grid input:focus,
.form-grid select:focus,
.form-grid textarea:focus {
  outline: none;
  border-color: var(--sm-focus);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sm-focus) 22%, transparent);
}

/* ---------- buttons ---------- */
button {
  font-family: inherit;
  font-size: 13px;
  font-weight: 550;
  padding: 7px 14px;
  border: 1px solid var(--sm-border-strong);
  border-radius: var(--sm-radius-sm);
  background: var(--sm-surface-raised);
  color: var(--sm-text);
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease, transform 0.06s ease;
}

button:hover {
  background: var(--sm-surface-sunken);
  border-color: var(--sm-border-strong);
}

button:active {
  transform: translateY(1px);
}

button:focus-visible,
a:focus-visible {
  outline: 2px solid var(--sm-focus);
  outline-offset: 2px;
}

button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    transition-duration: 0.01ms !important;
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
  }
}
</style>
