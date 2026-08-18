<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="app-shell">
    <aside class="app-sidebar">
      <div class="brand">
        <span class="brand-mark">S</span>
        <span class="brand-name">SupplyMind</span>
      </div>
      <nav class="side-nav">
        <div class="nav-group">
          <div class="nav-group-label">监测</div>
          <RouterLink to="/dashboard">总览</RouterLink>
          <RouterLink to="/history">历史趋势</RouterLink>
          <RouterLink to="/quality">数据质量</RouterLink>
        </div>
        <div class="nav-group">
          <div class="nav-group-label">数据管理</div>
          <RouterLink to="/sources">来源与录入</RouterLink>
          <RouterLink to="/config">动态配置</RouterLink>
        </div>
        <div class="nav-group">
          <div class="nav-group-label">运营分析</div>
          <RouterLink to="/warning">预警</RouterLink>
          <RouterLink to="/agent">Agent 工作台</RouterLink>
        </div>
      </nav>
      <div class="sidebar-foot muted">工业运营控制台</div>
    </aside>
    <div class="app-main">
      <RouterView />
    </div>
  </div>
</template>

<style>
/* ------------------------------------------------------------------
   Industrial Operations Console - token system (single source of
   truth for light + dark). Deep-blue primary, status semantics.
------------------------------------------------------------------- */
:root {
  --sm-navy: #0f2b46;
  --sm-navy-2: #173a5c;
  --sm-accent: #1e6fd9;
  --sm-accent-strong: #1558b0;
  --sm-accent-soft: #e4eefb;
  --sm-cyan: #0fa3b1;
  --sm-ok: #1e7d45;
  --sm-ok-bg: #e5f3ea;
  --sm-warn: #a05e00;
  --sm-warn-bg: #fdf1dd;
  --sm-bad: #bf2c33;
  --sm-bad-bg: #fdeaea;
  --sm-demo: #6a4fc4;
  --sm-demo-bg: #efeafb;
  --sm-border: #d9dee5;
  --sm-border-strong: #c3cbd5;
  --sm-text: #1c2530;
  --sm-text-2: #3f4a58;
  --sm-muted: #67717f;
  --sm-bg: #f2f4f7;
  --sm-surface: #ffffff;
  --sm-surface-2: #f6f8fa;
  --sm-surface-3: #eef1f5;
  --sm-focus: #1e6fd9;
  --sm-radius: 6px;
  --sm-shadow: 0 1px 2px rgba(15, 43, 70, 0.06), 0 1px 6px rgba(15, 43, 70, 0.05);
  --sm-font-mono: 'Cascadia Mono', 'JetBrains Mono', Consolas, 'Courier New', monospace;
  --sm-sidebar-w: 216px;
}

@media (prefers-color-scheme: dark) {
  :root {
    --sm-border: #303a46;
    --sm-border-strong: #425064;
    --sm-text: #e4e9f0;
    --sm-text-2: #c3ccd8;
    --sm-muted: #93a0b0;
    --sm-bg: #121821;
    --sm-surface: #1a212c;
    --sm-surface-2: #1f2834;
    --sm-surface-3: #171e28;
    --sm-accent: #5c9df0;
    --sm-accent-strong: #7cb5f5;
    --sm-accent-soft: #16283c;
    --sm-ok: #4cbf82;
    --sm-ok-bg: #13301f;
    --sm-warn: #e0a34a;
    --sm-warn-bg: #322712;
    --sm-bad: #e06a70;
    --sm-bad-bg: #37201f;
    --sm-demo: #a48fe0;
    --sm-demo-bg: #251f38;
    --sm-focus: #5c9df0;
    --sm-shadow: 0 1px 2px rgba(0, 0, 0, 0.3), 0 1px 6px rgba(0, 0, 0, 0.24);
  }
}

* { box-sizing: border-box; }
html { -webkit-text-size-adjust: 100%; }
body {
  margin: 0;
  font-family: 'Segoe UI', 'Microsoft YaHei', system-ui, -apple-system, sans-serif;
  font-size: 14px;
  line-height: 1.5;
  color: var(--sm-text);
  background: var(--sm-bg);
}

/* ---------- shell: fixed left nav + content ---------- */
.app-shell {
  display: flex;
  min-height: 100dvh;
}

.app-sidebar {
  width: var(--sm-sidebar-w);
  flex-shrink: 0;
  background: var(--sm-navy);
  color: #dfe8f2;
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100dvh;
}

@media (prefers-color-scheme: dark) {
  .app-sidebar { background: #0b1a2b; }
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 18px 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.brand-mark {
  width: 26px;
  height: 26px;
  border-radius: 6px;
  background: var(--sm-accent);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 14px;
}
.brand-name {
  font-size: 15px;
  font-weight: 650;
  letter-spacing: 0.01em;
}

.side-nav {
  flex: 1;
  overflow-y: auto;
  padding: 14px 10px;
}
.nav-group { margin-bottom: 18px; }
.nav-group-label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  color: #93a5b8;
  padding: 0 8px 6px;
}
.side-nav a {
  display: block;
  color: #c3d0dd;
  text-decoration: none;
  padding: 8px 10px;
  font-size: 13.5px;
  border-radius: var(--sm-radius);
  margin-bottom: 2px;
  transition: background-color 0.12s ease, color 0.12s ease;
}
.side-nav a:hover { background: rgba(255, 255, 255, 0.08); color: #fff; }
.side-nav a.router-link-active {
  background: var(--sm-accent);
  color: #fff;
  font-weight: 600;
}
.side-nav a:focus-visible,
a:focus-visible,
button:focus-visible,
input:focus-visible,
select:focus-visible,
textarea:focus-visible {
  outline: 2px solid var(--sm-focus);
  outline-offset: 2px;
}

.sidebar-foot {
  padding: 12px 18px;
  font-size: 11.5px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  color: #7d92a8;
}

.app-main {
  flex: 1;
  min-width: 0;
  padding: 26px 30px 40px;
  max-width: 1320px;
}

/* ---------- page hierarchy ---------- */
.page-head {
  margin-bottom: 18px;
}
.page-eyebrow {
  font-size: 11.5px;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  color: var(--sm-accent);
  font-weight: 600;
  margin-bottom: 4px;
}
.page-title {
  margin: 0;
  font-size: 21px;
  font-weight: 700;
  letter-spacing: -0.01em;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.page-desc {
  margin: 6px 0 0;
  color: var(--sm-muted);
  font-size: 13px;
  max-width: 72ch;
}
.page-actions {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.summary-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px;
  margin-bottom: 18px;
}
.summary-item {
  background: var(--sm-surface);
  border: 1px solid var(--sm-border);
  border-radius: var(--sm-radius);
  padding: 10px 14px;
}
.summary-item .summary-label {
  font-size: 11.5px;
  color: var(--sm-muted);
  margin-bottom: 2px;
}
.summary-item .summary-value {
  font-size: 17px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}

/* ---------- sections ---------- */
.section {
  background: var(--sm-surface);
  border: 1px solid var(--sm-border);
  border-radius: var(--sm-radius);
  margin-bottom: 16px;
  overflow: hidden;
}
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--sm-border);
  background: var(--sm-surface-2);
}
.section-head h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
}
.section-head .muted { font-size: 12px; }
.section-body { padding: 14px 16px; }
.section-body.flat { padding: 0; }
.section-sub { margin-top: 18px; }

/* ---------- tables ---------- */
.table-scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
table.sm-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  background: var(--sm-surface);
}
table.sm-table th,
table.sm-table td {
  padding: 9px 12px;
  text-align: left;
  border-bottom: 1px solid var(--sm-border);
  vertical-align: top;
}
table.sm-table th {
  background: var(--sm-surface-2);
  font-weight: 600;
  color: var(--sm-text-2);
  font-size: 12px;
  white-space: nowrap;
  position: sticky;
  top: 0;
  z-index: 1;
}
table.sm-table tbody tr { transition: background-color 0.1s ease; }
table.sm-table tbody tr:hover { background: var(--sm-accent-soft); }
table.sm-table .num {
  font-family: var(--sm-font-mono);
  font-variant-numeric: tabular-nums;
  text-align: right;
  white-space: nowrap;
}
table.sm-table .id-cell {
  font-family: var(--sm-font-mono);
  font-size: 12px;
  white-space: nowrap;
}
table.sm-table .cell-truncate {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------- buttons (clear hierarchy) ---------- */
button {
  font-family: inherit;
  font-size: 13px;
  font-weight: 550;
  padding: 7px 14px;
  border-radius: var(--sm-radius);
  cursor: pointer;
  transition: background-color 0.12s ease, border-color 0.12s ease, transform 0.06s ease;
}
button:active { transform: translateY(1px); }
button:disabled { opacity: 0.55; cursor: not-allowed; }
.btn-primary {
  background: var(--sm-accent);
  border: 1px solid var(--sm-accent);
  color: #fff;
}
.btn-primary:hover { background: var(--sm-accent-strong); border-color: var(--sm-accent-strong); }
.btn-secondary {
  background: var(--sm-surface);
  border: 1px solid var(--sm-border-strong);
  color: var(--sm-text);
}
.btn-secondary:hover { background: var(--sm-surface-3); }
.btn-ghost {
  background: transparent;
  border: 1px solid transparent;
  color: var(--sm-text-2);
}
.btn-ghost:hover { background: var(--sm-surface-3); }
.btn-danger {
  background: var(--sm-bad);
  border: 1px solid var(--sm-bad);
  color: #fff;
}
.btn-danger:hover { filter: brightness(0.94); }
.btn-demo {
  background: var(--sm-demo);
  border: 1px solid var(--sm-demo);
  color: #fff;
}

/* ---------- forms ---------- */
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 12px;
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
  border-radius: var(--sm-radius);
  background: var(--sm-surface);
  color: var(--sm-text);
  transition: border-color 0.12s ease, box-shadow 0.12s ease;
}
.form-grid input:focus,
.form-grid select:focus,
.form-grid textarea:focus {
  outline: none;
  border-color: var(--sm-focus);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sm-focus) 22%, transparent);
}
.form-grid input::placeholder,
.form-grid textarea::placeholder { color: var(--sm-muted); }
.form-actions { margin-top: 14px; display: flex; gap: 8px; }

/* ---------- status / banners ---------- */
.muted { color: var(--sm-muted); font-size: 13px; }
.error-banner {
  border: 1px solid var(--sm-bad);
  background: var(--sm-bad-bg);
  color: var(--sm-bad);
  padding: 10px 12px;
  border-radius: var(--sm-radius);
  margin-bottom: 12px;
  font-size: 13px;
}
.demo-banner {
  border: 1px solid var(--sm-demo);
  background: var(--sm-demo-bg);
  color: var(--sm-demo);
  padding: 8px 12px;
  border-radius: var(--sm-radius);
  margin-bottom: 12px;
  font-size: 13px;
  font-weight: 600;
}
.empty-state {
  padding: 26px 16px;
  text-align: center;
  color: var(--sm-muted);
  font-size: 13px;
}

/* ---------- responsive ---------- */
@media (max-width: 900px) {
  .app-shell { flex-direction: column; }
  .app-sidebar {
    width: 100%;
    height: auto;
    position: static;
    flex-direction: row;
    align-items: center;
    gap: 8px;
    padding: 8px 12px;
    overflow-x: auto;
  }
  .brand { border-bottom: none; padding: 6px 8px; }
  .side-nav {
    display: flex;
    flex-direction: row;
    gap: 4px;
    padding: 0;
    overflow-x: auto;
  }
  .nav-group { display: flex; gap: 4px; margin: 0; }
  .nav-group-label { display: none; }
  .side-nav a { white-space: nowrap; margin: 0; }
  .sidebar-foot { display: none; }
  .app-main { padding: 16px 14px 32px; }
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
