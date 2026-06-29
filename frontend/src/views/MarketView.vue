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

      <!-- 紧凑股票列表（横向滚动） -->
      <div class="compact-view">
        <div class="compact-scroll">
          <table class="compact-table">
            <tbody>
              <tr>
                <td class="row-label-cell">名称</td>
                <td v-for="stock in filteredMatches" :key="'n-' + stock.id" class="stock-col" @click="onSymbolClick(stock.symbol)">
                  <span class="cell-name">{{ stock.name || '—' }}</span>
                </td>
              </tr>
              <tr>
                <td class="row-label-cell">代码</td>
                <td v-for="stock in filteredMatches" :key="'c-' + stock.id" class="stock-col" @click="onSymbolClick(stock.symbol)">
                  <span class="cell-symbol">{{ stock.symbol }}</span>
                </td>
              </tr>
              <tr>
                <td class="row-label-cell"></td>
                <td v-for="stock in filteredMatches" :key="'cp-' + stock.id" class="copy-col">
                  <span v-if="copiedSymbol === stock.symbol" style="color:#52c41a;font-size:11px">✓ 已复制</span>
                  <a v-else
                    style="font-size:11px;cursor:pointer;color:#1890ff"
                    @click.stop="copySymbol(stock.symbol)">复制</a>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="compact-footer">
          <span class="info">共 {{ filteredMatches.length }} 条·点击查看K线</span>
        </div>
      </div>

      <!-- K线图表卡片 -->
      <div v-if="showCandleChart" class="candle-chart-card">
        <div class="candle-chart-header">
          <span class="candle-chart-title">📈 {{ selectedSymbol }} K线图（近30天）</span>
          <div class="candle-chart-actions">
            <NButton size="tiny" quaternary @click="showCandleChart = false">✕ 关闭</NButton>
          </div>
        </div>
        <div class="candle-chart-body">
          <div v-if="candleLoading" style="text-align:center;padding:40px;">
            <NSpin size="small" />
            <p style="margin-top:8px;color:#999;font-size:13px;">加载K线数据中...</p>
          </div>
          <VChart v-else-if="candleData.length > 0" :option="candleChartOption" autoresize style="width:100%;height:400px;" />
          <div v-else style="text-align:center;padding:40px;color:#999;">
            暂无 K 线数据
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { NSpin, NButton, useNotification } from 'naive-ui'
import StatCard from '../components/StatCard.vue'
import { fetchLatestNotification, fetchLatestScreening } from '../api/screening'
import type { ScreeningMatch, NotificationResult, LatestScreening } from '../api/screening'
import VChart from 'vue-echarts'
import { useCandleChart } from '../composables/useCandleChart'

const notification = useNotification()

// ============ 状态 ============
const loading = ref(true)
const notificationData = ref<NotificationResult | null>(null)
const screeningData = ref<LatestScreening | null>(null)
const selectedWindow = ref(2)
const selectedAlgo = ref('volume_spike')

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
      totalCount += typeof windowVal === "object" && windowVal !== null ? (windowVal as any).count : (windowVal as number)
    }
  }
  return { totalCount, detail: data.results }
})

/** 总匹配数 */
const totalMatches = computed(() => screeningData.value?.totalMatches || 0)

/** 筛选日期 */
const screeningDate = computed(() => screeningData.value?.tradeDate || '')

/** 批次 ID */
const batchIdShort = computed(() => {
  const id = screeningData.value?.batchId || notificationData.value?.batchId
  return id || ''
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

// ============ 股票筛选 ============
/** 筛选后的匹配列表 */
const filteredMatches = computed(() => {
  const matches = screeningData.value?.matches || []
  return matches.filter(m => {
    const matchWindow = m.windowDays === selectedWindow.value
    const matchAlgo = selectedAlgo.value === 'all' || m.algorithm === selectedAlgo.value
    return matchWindow && matchAlgo
  })
})

// ===================== K线图表（共享 composable） =====================
const {
  showCandleChart,
  candleLoading,
  candleData,
  selectedSymbol,
  copiedSymbol,
  onSymbolClick,
  copySymbol,
  candleChartOption
} = useCandleChart()

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
  background: var(--bg-card);
  border-radius: 10px;
  padding: 14px 20px;
  box-shadow: var(--shadow-sm);
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
  border: 1px solid var(--border-color);
  background: var(--bg-card);
  color: var(--text-secondary);
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

/* 紧凑股票列表（横向滚动） */
.compact-view {
  background: var(--bg-card);
  border-radius: 10px;
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.compact-scroll {
  overflow-x: auto;
  overflow-y: hidden;
  padding: 8px 0;
}

.compact-scroll::-webkit-scrollbar {
  height: 8px;
}
.compact-scroll::-webkit-scrollbar-track {
  background: transparent;
}
.compact-scroll::-webkit-scrollbar-thumb {
  background: rgba(255,255,255,0.15);
  border-radius: 4px;
}

.compact-table {
  border-collapse: collapse;
  white-space: nowrap;
}

.compact-table th,
.compact-table td {
  padding: 6px 14px;
  text-align: center;
  font-size: 13px;
}

.row-label-cell {
  font-weight: 600;
  color: var(--text-secondary);
  position: sticky;
  left: 0;
  background: var(--bg-card);
  z-index: 1;
  min-width: 44px;
  text-align: center;
}

.stock-col {
  cursor: pointer;
  transition: background 0.15s;
  user-select: none;
}
.stock-col:hover {
  background: var(--hover-bg);
}

.cell-symbol {
  font-weight: 600;
  color: #1890ff;
  font-family: 'Menlo', 'Consolas', monospace;
}

.cell-name {
  color: var(--text-primary);
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: inline-block;
  vertical-align: middle;
}

.compact-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 8px 20px;
  border-top: 1px solid var(--border-color);
  font-size: 12px;
  color: var(--text-tertiary);
}

.copy-col {
  text-align: center;
  padding: 2px 14px 6px;
  font-size: 11px;
}

/* === K线图表卡片 === */
.candle-chart-card {
  background: var(--bg-card);
  border-radius: 10px;
  box-shadow: var(--shadow-sm);
  margin-top: 20px;
  overflow: hidden;
}

.candle-chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--border-color);
}

.candle-chart-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.candle-chart-actions {
  display: flex;
  gap: 8px;
}

.candle-chart-body {
  padding: 16px;
}
</style>
