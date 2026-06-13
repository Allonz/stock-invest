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
      </div>

      <!-- 空状态 -->
      <div v-if="!hasData" class="empty-state">
        <div class="empty-icon">📊</div>
        <p>{{ statusMsg || '输入股票代码并点击查询, 如 TOVX' }}</p>
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
import { NInput, NButton, NTag, NDataTable } from 'naive-ui'
import { fetchBars } from '../api/bars'
import type { BarRecord } from '../api/bars'

// ============ 状态 ============
const symbol = ref('')
const lastSearched = ref('')
const statusMsg = ref('')
const hasData = ref(false)
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
  { title: '股票代码', key: 'symbol', width: 90, sorter: (a: BarRecord, b: BarRecord) => a.symbol.localeCompare(b.symbol) },
  { title: '日期', key: 'tradeDate', width: 120, defaultSortOrder: 'descend', sorter: (a: BarRecord, b: BarRecord) => a.tradeDate.localeCompare(b.tradeDate) },
  {
    title: '开盘价', key: 'openPrice', width: 110, sorter: (a: BarRecord, b: BarRecord) => a.openPrice - b.openPrice,
    render: (row: BarRecord) => formatPrice(row.openPrice)
  },
  {
    title: '收盘价', key: 'closePrice', width: 110, sorter: (a: BarRecord, b: BarRecord) => a.closePrice - b.closePrice,
    render: (row: BarRecord) => formatPrice(row.closePrice)
  },
  {
    title: '涨跌幅', key: 'changePercent', width: 110,
    sorter: (a: BarRecord, b: BarRecord) => {
      const pa = a.openPrice > 0 ? (a.closePrice - a.openPrice) / a.openPrice : 0
      const pb = b.openPrice > 0 ? (b.closePrice - b.openPrice) / b.openPrice : 0
      return pa - pb
    },
    render: (row: BarRecord) => {
      const pct = row.openPrice > 0 ? ((row.closePrice - row.openPrice) / row.openPrice * 100).toFixed(2) : '0.00'
      const isUp = row.closePrice >= row.openPrice
      return h(NTag, {
        type: isUp ? 'error' : 'success',
        size: 'small',
        bordered: false
      }, { default: () => `${isUp ? '+' : ''}${pct}%` })
    }
  },
  {
    title: '成交量', key: 'volume', width: 120, sorter: (a: BarRecord, b: BarRecord) => a.volume - b.volume,
    render: (row: BarRecord) => formatVolume(row.volume)
  },
  { title: '数据源', key: 'source', width: 100, sorter: (a: BarRecord, b: BarRecord) => a.source.localeCompare(b.source) }
]

// ============ 辅助函数 ============
function formatPrice(val: number): string {
  return '$' + val.toFixed(4)
}

function formatVolume(val: number): string {
  if (val >= 100_000_000) return (val / 100_000_000).toFixed(2) + '亿'
  if (val >= 10_000) return (val / 10_000).toFixed(2) + '万'
  return val.toString()
}

// ============ 查询 ============
let searching = false

async function handleSearch() {
  if (searching) return
  const code = symbol.value.trim().toUpperCase()
  if (!code) {
    statusMsg.value = '请输入股票代码'
    return
  }
  statusMsg.value = ''
  lastSearched.value = code
  searching = true
  hasData.value = false
  barData.value = []

  try {
    const res = await fetchBars(code)
    barData.value = res.data.rows
    hasData.value = res.data.rows.length > 0
    if (!hasData.value) {
      statusMsg.value = '未找到 ' + code + ' 的K线数据'
    }
  } catch (err: any) {
    statusMsg.value = '查询失败: ' + (err.message || '网络错误')
  } finally {
    searching = false
  }
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
