<template>
  <!-- K线数据查询页面 -->
  <div class="bars-view">
    <div class="search-card">
      <div class="search-form">
        <span style="font-size: 14px; font-weight: 500; color: #333;">股票代码</span>
        <NInput
          v-model:value="symbol"
          placeholder="输入股票代码, 如: AAPL"
          :style="{ width: '200px' }"
          clearable
          @keyup.enter="handleSearch"
        />
        <NButton type="primary" @click="handleSearch">🔍 查询</NButton>
      </div>
      <div class="search-info">
        <NTag v-if="lastSearched" size="small" type="info">
          上次查询: {{ lastSearched }}
        </NTag>
      </div>
    </div>

    <!-- 数据表格 -->
    <div class="table-container">
      <div class="table-header">
        <span class="title">📈 K线数据 · {{ symbol || '—' }}</span>
        <div class="actions">
          <NButton size="tiny" @click="loadSampleData">📊 示例数据</NButton>
        </div>
      </div>

      <!-- 空状态 -->
      <div v-if="!hasData" class="empty-state">
        <div class="empty-icon">📊</div>
        <p>输入股票代码并点击查询</p>
        <p style="font-size: 12px; color: #ccc; margin-top: 4px;">目前为静态展示, 等待 API 对接</p>
      </div>

      <NDataTable
        v-else
        :columns="columns"
        :data="barData"
        :pagination="pagination"
        :bordered="false"
        :single-line="false"
        size="small"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, h } from 'vue'
import { NInput, NButton, NTag, NDataTable, useNotification } from 'naive-ui'

const notification = useNotification()

// ============ 状态 ============
const symbol = ref('')
const lastSearched = ref('')
const hasData = ref(false)
interface BarRecord {
  id: number
  date: string
  open: number
  high: number
  low: number
  close: number
  volume: number
}
const barData = ref<BarRecord[]>([])

// ============ 分页配置 ============
const pagination = reactive({
  page: 1,
  pageSize: 15,
  showSizePicker: true,
  pageSizes: [10, 15, 30, 50],
  onChange: (page: number) => { pagination.page = page },
  onUpdatePageSize: (size: number) => {
    pagination.pageSize = size
    pagination.page = 1
  }
})

// ============ 表格列定义 ============
const columns = [
  { title: '日期', key: 'date', width: 120, sorter: true },
  {
    title: '开盘', key: 'open', width: 110, sorter: true,
    render: (row: BarRecord) => formatPrice(row.open)
  },
  {
    title: '最高', key: 'high', width: 110, sorter: true,
    render: (row: BarRecord) => h('span', { style: 'color: #f5222d; font-weight: 600;' }, formatPrice(row.high))
  },
  {
    title: '最低', key: 'low', width: 110, sorter: true,
    render: (row: BarRecord) => h('span', { style: 'color: #52c41a; font-weight: 600;' }, formatPrice(row.low))
  },
  {
    title: '收盘', key: 'close', width: 110, sorter: true,
    render: (row: BarRecord) => formatPrice(row.close)
  },
  {
    title: '涨跌幅', key: 'changePercent', width: 110, sorter: true,
    render: (row: BarRecord) => {
      const pct = ((row.close - row.open) / row.open * 100).toFixed(2)
      const isUp = row.close >= row.open
      return h(NTag, {
        type: isUp ? 'error' : 'success',
        size: 'small',
        bordered: false
      }, { default: () => `${isUp ? '+' : ''}${pct}%` })
    }
  },
  {
    title: '成交量', key: 'volume', width: 120, sorter: true,
    render: (row: BarRecord) => formatVolume(row.volume)
  }
]

// ============ 辅助函数 ============
function formatPrice(val: number): string {
  return `$${val.toFixed(4)}`
}

function formatVolume(val: number): string {
  if (val >= 1_000_000) return (val / 1_000_000).toFixed(1) + 'M'
  if (val >= 1_000) return (val / 1_000).toFixed(1) + 'K'
  return val.toString()
}

// ============ 方法 ============
/** 查询 K线数据 */
async function handleSearch() {
  const code = symbol.value.trim().toUpperCase()
  if (!code) {
    notification.warning({ title: '请输入股票代码', duration: 2000 })
    return
  }
  lastSearched.value = code
  notification.info({ title: '查询', content: `正在查询 ${code} 的K线数据...`, duration: 2000 })
  // TODO: 对接后端 API GET /api/bars?symbol=XXX
  // 目前先用示例数据展示
  loadSampleData()
}

/** 加载示例 K线数据 */
function loadSampleData() {
  const sample: BarRecord[] = []
  const basePrice = symbol.value ? 0.3 : 0.4329
  const startDate = new Date('2026-05-01')

  for (let i = 0; i < 20; i++) {
    const date = new Date(startDate)
    date.setDate(date.getDate() + i)
    // 跳过周末
    if (date.getDay() === 0 || date.getDay() === 6) continue

    const volatility = basePrice * 0.05
    const open = basePrice + (Math.random() - 0.5) * volatility
    const close = open + (Math.random() - 0.5) * volatility * 2
    const high = Math.max(open, close) + Math.abs(volatility * Math.random())
    const low = Math.min(open, close) - Math.abs(volatility * Math.random())
    const volume = Math.floor(Math.random() * 100000) + 10000

    sample.push({
      id: i,
      date: date.toISOString().split('T')[0],
      open: Math.round(open * 10000) / 10000,
      high: Math.round(high * 10000) / 10000,
      low: Math.round(low * 10000) / 10000,
      close: Math.round(close * 10000) / 10000,
      volume
    })
  }

  barData.value = sample
  hasData.value = true
  notification.success({ title: '数据加载完成', content: `共 ${sample.length} 条K线数据`, duration: 2000 })
}
</script>

<style scoped>
.bars-view {
  min-height: 200px;
}

/* 搜索卡片 */
.search-card {
  background: #fff;
  border-radius: 10px;
  padding: 16px 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  margin-bottom: 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

.search-form {
  display: flex;
  align-items: center;
  gap: 12px;
}

.search-info {
  display: flex;
  align-items: center;
  gap: 8px;
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

/* 空状态 */
.empty-state {
  padding: 60px 20px;
  text-align: center;
  color: #999;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 12px;
}
</style>
