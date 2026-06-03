<template>
  <!-- 实时行情页面 -->
  <div class="market-view">
    <!-- 加载中状态 -->
    <div v-if="loading" class="loading-container">
      <NSpin size="large" />
      <p style="margin-top: 16px; color: #999">加载数据中...</p>
    </div>

    <template v-else>
      <!-- 顶部统计卡片 -->
      <div class="stat-cards">
        <StatCard
          label="今日筛选数"
          :value="notificationStats.totalCount"
          color="blue"
          trend="滚动统计"
        />
        <StatCard
          label="命中数量"
          :value="totalMatches"
          color="green"
          trend="当前批次"
        />
        <StatCard
          label="数据日期"
          :value="screeningDate"
          color="orange"
          :trend-up="true"
          :trend="`批次: ${batchIdShort}`"
        />
        <StatCard
          label="匹配算法"
          :value="algoCount"
          color="red"
          trend="条匹配记录"
        />
      </div>

      <!-- 筛选条件栏 -->
      <div class="filter-bar">
        <span style="font-size:13px;color:#666;font-weight:500;">窗口：</span>
        <span
          v-for="d in windowOptions"
          :key="d"
          class="filter-tag"
          :class="{ active: selectedWindow === d }"
          @click="selectedWindow = d; loadData()"
        >
          {{ d }}天
        </span>
        <div class="spacer"></div>
        <span style="font-size:13px;color:#666;font-weight:500;margin-left:12px;">算法：</span>
        <span
          v-for="algo in algoOptions"
          :key="algo"
          class="filter-tag"
          :class="{ active: selectedAlgo === algo }"
          @click="selectedAlgo = algo; loadData()"
        >
          {{ algo }}
        </span>
        <div class="spacer"></div>
        <NButton type="primary" size="small" @click="loadData">🔄 刷新</NButton>
      </div>

      <!-- 数据表格 -->
      <div class="table-container">
        <div class="table-header">
          <span class="title">📋 筛选结果 · {{ screeningDate || '—' }}</span>
          <div class="actions">
            <NButton size="tiny" @click="handleExport">📥 导出 CSV</NButton>
          </div>
        </div>
        <NDataTable
          :columns="tableColumns"
          :data="filteredMatches"
          :pagination="pagination"
          :bordered="false"
          :single-line="false"
          size="small"
          :row-key="(row: any) => row.id"
        />
        <div class="table-footer">
          <span class="info">共 {{ filteredMatches.length }} 条记录</span>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, h } from 'vue'
import { NSpin, NButton, NDataTable, useNotification, NTag } from 'naive-ui'
import StatCard from '../components/StatCard.vue'
import { fetchLatestNotification, fetchLatestScreening } from '../api/screening'
import type { ScreeningMatch, NotificationResult, LatestScreening } from '../api/screening'

const notification = useNotification()

// ============ 状态 ============
const loading = ref(true)
const notificationData = ref<NotificationResult | null>(null)
const screeningData = ref<LatestScreening | null>(null)
const selectedWindow = ref(2)
const selectedAlgo = ref('all')

/** 窗口选项 */
const windowOptions = [2, 3, 4, 5, 6, 7]
/** 算法选项 */
const algoOptions = ['all', 'increasing_volume', 'volume_spike']

// ============ 统计卡片数据 ============
/** 统计总数 */
const notificationStats = computed(() => {
  const data = notificationData.value
  if (!data?.results) return { totalCount: 0, detail: {} }
  let totalCount = 0
  for (const algo of Object.values(data.results)) {
    for (const windowVal of Object.values(algo)) {
      // new format: { count: 74, stocks: [...] }; old format: 74 (backwards compat)
      totalCount += typeof windowVal === "object" && windowVal !== null ? windowVal.count : windowVal
    }
  }
  return { totalCount, detail: data.results }
})

/** 总匹配数 */
const totalMatches = computed(() => screeningData.value?.totalMatches || 0)

/** 筛选日期 */
const screeningDate = computed(() => screeningData.value?.tradeDate || '')

/** 批次 ID 简短版 */
const batchIdShort = computed(() => {
  const id = screeningData.value?.batchId || notificationData.value?.batchId
  return id ? id.slice(0, 12) + '...' : ''
})

/** 算法统计 */
const algoCount = computed(() => {
  const matches = screeningData.value?.matches
  if (!matches) return 0
  // 按算法分组统计
  const algoMap: Record<string, number> = {}
  matches.forEach(m => {
    algoMap[m.algorithm] = (algoMap[m.algorithm] || 0) + 1
  })
  return Object.entries(algoMap).map(([k, v]) => `${k}=${v}`).join(', ')
})

// ============ 表格 ============
/** 筛选后的匹配列表 */
const filteredMatches = computed(() => {
  const matches = screeningData.value?.matches || []
  return matches.filter(m => {
    const matchWindow = m.windowDays === selectedWindow.value
    const matchAlgo = selectedAlgo.value === 'all' || m.algorithm === selectedAlgo.value
    return matchWindow && matchAlgo
  })
})

/** 分页配置 */
const pagination = reactive({
  page: 1,
  pageSize: 10,
  showSizePicker: true,
  pageSizes: [5, 10, 20, 50],
  onChange: (page: number) => { pagination.page = page },
  onUpdatePageSize: (size: number) => {
    pagination.pageSize = size
    pagination.page = 1
  }
})

/** 创建表格列定义 */
const tableColumns = computed(() => [
  { title: '代码', key: 'symbol', width: 120, align: 'center' as const, sorter: true, render: (row: ScreeningMatch) => {
        const copied = copiedSymbol.value === row.symbol
        return [
          h('span', { class: 'symbol-cell' }, row.symbol),
          copied
            ? h('span', { style: 'color:#52c41a;font-size:12px;margin-left:24px' }, '✓ 复制成功')
            : h('a', {
                style: 'margin-left:24px;cursor:pointer;color:#1890ff;font-size:12px',
                onClick: () => { copySymbol(row.symbol); return false; }
              }, '复制')
        ]
      } },
  { title: '名称', key: 'name', width: 180, align: 'center' as const, render: (row: ScreeningMatch) => row.name || '—' },
  { title: '最新价', key: 'lastClose', width: 120, align: 'center' as const, sorter: true, render: (row: ScreeningMatch) => h('span', { class: 'price-cell' }, `$${row.lastClose.toFixed(4)}`) },
  {
    title: '涨幅', key: 'rise', width: 100, align: 'center' as const,
    render: (row: ScreeningMatch) => {
      return h(NTag, {
        type: row.rise ? 'error' : 'success',
        size: 'small',
        bordered: false
      }, { default: () => row.rise ? '↑ 上涨' : '↓ 下跌' })
    }
  },
  { title: '算法', key: 'algorithm', width: 150, align: 'center' as const,
    render: (row: ScreeningMatch) => {
      return h(NTag, {
        color: { color: '#f9f0ff', textColor: '#722ed1' },
        size: 'small',
        bordered: false
      }, { default: () => row.algorithm === 'increasing_volume' ? '📈 放量上涨' : '⚡ 量能异动' })
    }
  },
  { title: '窗口', key: 'windowDays', width: 80, align: 'center' as const,
    render: (row: ScreeningMatch) => `${row.windowDays}天`
  }
])

// ============ 复制反馈 ============
const copiedSymbol = ref<string | null>(null)
function copySymbol(sym: string) {
  navigator.clipboard.writeText(sym)
  copiedSymbol.value = sym
  setTimeout(() => { copiedSymbol.value = null }, 1000)
}

// ============ 数据加载 ============
/** 加载所有数据 */
async function loadData() {
  loading.value = true
  try {
    const [notifRes, screenRes] = await Promise.allSettled([
      fetchLatestNotification(),
      fetchLatestScreening()
    ])

    if (notifRes.status === 'fulfilled' && notifRes.value.data.success) {
      notificationData.value = notifRes.value.data.data
    } else {
      notification.warning({ title: '通知数据加载失败', content: '使用默认统计数据', duration: 3000 })
    }

    if (screenRes.status === 'fulfilled' && screenRes.value.data.success) {
      screeningData.value = screenRes.value.data.data
    } else {
      notification.error({ title: '筛选数据加载失败', content: '请稍后重试', duration: 3000 })
    }
  } catch (err: any) {
    notification.error({ title: '请求失败', content: err.message || '网络异常', duration: 3000 })
  } finally {
    loading.value = false
  }
}

/** 导出 CSV */
function handleExport() {
  const matches = filteredMatches.value
  if (matches.length === 0) {
    notification.warning({ title: '无数据可导出', duration: 2000 })
    return
  }
  const headers = ['代码', '名称', '最新价', '上涨', '算法', '窗口']
  const rows = matches.map(m => [m.symbol, m.name || '', m.lastClose, m.rise ? '是' : '否', m.algorithm, m.windowDays])
  const csv = [headers.join(','), ...rows.map(r => r.join(','))].join('\n')
  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `screening_${screeningData.value?.tradeDate || 'data'}.csv`
  a.click()
  URL.revokeObjectURL(url)
  notification.success({ title: '导出成功', duration: 2000 })
}

// 初始化加载
onMounted(loadData)
</script>

<style scoped>
.market-view {
  min-height: 200px;
}

.loading-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
}

.stat-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

@media (max-width: 900px) {
  .stat-cards {
    grid-template-columns: repeat(2, 1fr);
  }
}

/* 筛选栏 */
.filter-bar {
  background: #fff;
  border-radius: 10px;
  padding: 14px 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  margin-bottom: 20px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.filter-tag {
  padding: 5px 14px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  border: 1px solid #d9d9d9;
  background: #fff;
  color: #666;
  transition: all 0.2s;
  user-select: none;
}

.filter-tag.active {
  background: #1890ff;
  color: #fff;
  border-color: #1890ff;
}

.filter-tag:hover:not(.active) {
  border-color: #1890ff;
  color: #1890ff;
}

.spacer {
  flex: 1;
  min-width: 8px;
}

/* 表格容器 */
.table-container {
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid #f0f0f0;
}

.table-header .title {
  font-size: 15px;
  font-weight: 600;
}

.table-header .actions {
  display: flex;
  gap: 8px;
}

.table-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 12px 20px;
  border-top: 1px solid #f0f0f0;
}

.table-footer .info {
  font-size: 12px;
  color: #999;
}

/* 表格内样式 */
:deep(.symbol-cell) {
  font-weight: 600;
  color: #1890ff;
}

:deep(.price-cell) {
  font-family: 'Menlo', 'Consolas', monospace;
  font-weight: 600;
}
</style>
