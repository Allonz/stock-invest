<template>
  <!-- 全局布局: 左侧深色侧边栏 + 右侧白色内容区 -->
  <div class="layout">
    <!-- ============ 左侧导航栏 ============ -->
    <aside class="sidebar">
      <!-- 侧边栏头部 -->
      <div class="sidebar-header">
        <div class="logo">📊</div>
        <span class="title">低价股行情系统</span>
      </div>

      <!-- 导航菜单 -->
      <nav class="nav">
        <div class="nav-group-label">数据</div>
        <router-link
          v-for="item in dataNavItems"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: currentRoute === item.path }"
        >
          <span class="icon">{{ item.icon }}</span>
          <span>{{ item.label }}</span>
        </router-link>

        <div class="nav-group-label" style="margin-top: 16px">管理</div>
        <router-link
          v-for="item in mgmtNavItems"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: currentRoute === item.path }"
        >
          <span class="icon">{{ item.icon }}</span>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>

      <!-- 底部状态 -->
      <div class="sidebar-footer">
        <span class="dot"></span>
        <span>服务在线 · v1.0</span>
      </div>
    </aside>

    <!-- ============ 右侧主内容区 ============ -->
    <div class="main-content">
      <!-- 顶栏 -->
      <header class="topbar">
        <div class="topbar-left">
          <span class="breadcrumb">{{ route.meta?.breadcrumb || '' }} /</span>
          <span class="page-title">{{ route.meta?.title || '' }}</span>
        </div>
        <div class="topbar-right">
          <div class="search-box" @click="handleSearchClick">
            🔍 搜索股票代码...
          </div>
          <div class="avatar">A</div>
        </div>
      </header>

      <!-- 内容区域 -->
      <main class="content-area">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

/** 当前路由路径 */
const currentRoute = computed(() => route.path)

/** 数据菜单项 */
const dataNavItems = [
  { path: '/market', label: '实时行情', icon: '📊' },
  { path: '/bars', label: 'K线数据', icon: '📈' }
]

/** 管理菜单项 */
const mgmtNavItems = [
  { path: '/fill-tasks', label: '补填任务', icon: '🔄' },
  { path: '/screener', label: '行情筛选', icon: '🔍' },
  { path: '/datasource-status', label: '数据源状态', icon: '📡' },
  { path: '/trading-calendar', label: '开盘日历', icon: '📅' }
]

/** 点击搜索栏跳转到 K线数据页 */
function handleSearchClick() {
  router.push('/bars')
}
</script>

<style scoped>
/* ============ 整体布局 ============ */
.layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* ============ 侧边栏 ============ */
.sidebar {
  width: 220px;
  background: linear-gradient(180deg, #1a1a2e 0%, #16213e 100%);
  color: #fff;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  position: relative;
  z-index: 10;
}

.sidebar-header {
  padding: 20px 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  display: flex;
  align-items: center;
  gap: 10px;
}

.sidebar-header .logo {
  width: 36px;
  height: 36px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}

.sidebar-header .title {
  font-size: 15px;
  font-weight: 600;
}

/* 导航 */
.nav {
  flex: 1;
  padding: 12px 0;
  overflow-y: auto;
}

.nav-group-label {
  padding: 8px 20px 4px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.4);
  text-transform: uppercase;
  letter-spacing: 1px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 20px;
  margin: 2px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.7);
  text-decoration: none;
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}

.nav-item.active {
  background: rgba(102, 126, 234, 0.3);
  color: #fff;
  font-weight: 500;
}

.nav-item .icon {
  font-size: 16px;
  width: 24px;
  text-align: center;
}

/* 底部状态 */
.sidebar-footer {
  padding: 12px 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.5);
}

.sidebar-footer .dot {
  width: 8px;
  height: 8px;
  background: #52c41a;
  border-radius: 50%;
}

/* ============ 主内容区 ============ */
.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #f0f2f5;
}

/* 顶栏 */
.topbar {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  flex-shrink: 0;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.topbar-left .breadcrumb {
  font-size: 13px;
  color: #999;
}

.topbar-left .page-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.search-box {
  display: flex;
  align-items: center;
  gap: 6px;
  background: #f5f5f5;
  border-radius: 6px;
  padding: 6px 12px;
  font-size: 13px;
  color: #999;
  cursor: pointer;
  transition: background 0.2s;
}

.search-box:hover {
  background: #ebebeb;
  color: #666;
}

.avatar {
  width: 32px;
  height: 32px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: #fff;
  font-weight: 600;
}

/* 内容区域 */
.content-area {
  flex: 1;
  padding: 20px 24px;
  overflow-y: auto;
}
</style>
