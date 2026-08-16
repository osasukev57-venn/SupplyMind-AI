<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="app">
    <header class="app-header">
      <span class="app-title">SupplyMind 监测仪表盘</span>
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
:root {
  --sm-border: #d9d9d9;
  --sm-text: #1f2329;
  --sm-muted: #646a73;
  --sm-bg: #f5f6f7;
  --sm-ok: #2e7d32;
  --sm-warn: #b26a00;
  --sm-bad: #c62828;
  --sm-accent: #1565c0;
}
* {
  box-sizing: border-box;
}
body {
  margin: 0;
  font-family: 'Segoe UI', 'Microsoft YaHei', system-ui, sans-serif;
  color: var(--sm-text);
  background: var(--sm-bg);
}
.app-header {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 12px 24px;
  background: #1c2b3a;
  color: #fff;
}
.app-title {
  font-size: 16px;
  font-weight: 600;
}
.app-nav a {
  color: #c8d2dc;
  text-decoration: none;
  margin-right: 16px;
  font-size: 14px;
}
.app-nav a.router-link-active {
  color: #fff;
  font-weight: 600;
}
.app-main {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
}
.panel {
  background: #fff;
  border: 1px solid var(--sm-border);
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
}
.panel h2 {
  margin: 0 0 12px;
  font-size: 15px;
}
table.sm-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
table.sm-table th,
table.sm-table td {
  border: 1px solid var(--sm-border);
  padding: 6px 8px;
  text-align: left;
  word-break: break-all;
}
table.sm-table th {
  background: #f0f2f5;
}
.muted {
  color: var(--sm-muted);
}
.error-banner {
  border: 1px solid var(--sm-bad);
  background: #fdecea;
  color: var(--sm-bad);
  padding: 10px 12px;
  border-radius: 6px;
  margin-bottom: 12px;
  font-size: 13px;
}
.demo-banner {
  border: 1px solid var(--sm-warn);
  background: #fff4e5;
  color: var(--sm-warn);
  padding: 8px 12px;
  border-radius: 6px;
  margin-bottom: 12px;
  font-size: 13px;
  font-weight: 600;
}
</style>
