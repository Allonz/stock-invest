// 路由配置
import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router'

// 定义路由表
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/market'
  },
  {
    path: '/market',
    name: 'Market',
    component: () => import('../views/MarketView.vue'),
    meta: { title: '行情看板', breadcrumb: '数据', icon: '📊' }
  },
  {
    path: '/screener',
    name: 'Screener',
    component: () => import('../views/ScreenerView.vue'),
    meta: { title: '行情筛选', breadcrumb: '管理', icon: '🔍' }
  },
  {
    path: '/bars',
    name: 'Bars',
    component: () => import('../views/BarsView.vue'),
    meta: { title: 'K线查询', breadcrumb: '数据', icon: '📈' }
  },
  {
    path: '/fill-tasks',
    name: 'FillTasks',
    component: () => import('../views/FillTasks.vue'),
    meta: { title: '补缺任务', breadcrumb: '管理', icon: '🛠' }
  },
  {
    path: '/datasource-status',
    name: 'DataSourceStatus',
    component: () => import('../views/DataSourceStatus.vue'),
    meta: { title: '数据源状态', breadcrumb: '管理', icon: '🔌' }
  },
  {
    path: '/trading-calendar',
    name: 'TradingCalendar',
    component: () => import('../views/TradingCalendarView.vue'),
    meta: { title: '开盘日历', breadcrumb: '管理', icon: '📅' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
