import { createRouter, createWebHashHistory } from 'vue-router'

/**
 * D7 router. Hash history keeps the app portable (Day9 Electron packaging) and avoids server
 * rewrites on the static host.
 */
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    {
      path: '/dashboard',
      name: 'dashboard',
      component: () => import('../views/DashboardView.vue')
    },
    {
      path: '/history',
      name: 'history',
      component: () => import('../views/HistoryView.vue')
    },
    {
      path: '/quality',
      name: 'quality',
      component: () => import('../views/QualityView.vue')
    },
    {
      path: '/sources',
      name: 'sources',
      component: () => import('../views/SourcesView.vue')
    },
    {
      path: '/config',
      name: 'config',
      component: () => import('../views/ConfigView.vue')
    }
  ]
})
