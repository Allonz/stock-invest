<template>
  <!-- K线数据查询页面 -->
  <div class="bars-view">
    <div class="search-card">
      <div class="filter-row">
        <span class="filter-label">股票代码</span>
        <NInput
          v-model:value="symbol"
          placeholder="输入股票代码"
          :style="{ width: '160px' }"
          clearable
          @keyup.enter="applyFilters"
        />
        <span class="filter-label">交易日</span>
        <NDatePicker
          v-model:value="filterTradeDateTs"
          type="date"
          placeholder="选择日期"
          :style="{ width: '160px' }"
          clearable
          @update:value="applyFilters"
        />
        <span class="filter-label">数据源</span>
        <NSelect
          v-model:value="filterSource"
          :options="sourceOptions"
          placeholder="全部"
          clearable
          :style="{ width: '140px' }"
          @update:value="applyFilters"
        />
        <NButton type="primary" @click="applyFilters">🔍 查询</NButton>
        <NButton v-if="hasActiveFilters" @click="resetFilters">重置</NButton>
      </div>
    </div>

    <!-- 数据表格 -->
    <div class="table-container">
      <div class="table-header">
        <span class="title">📈 K线数据 · {{ pageTitle }}</span>
      </div>

      <NDataTable
        :columns="columns"
        :data="barData"
        :remote="true"
        :loading="loading"
        :pagination="pagination"
        :bordered="false"
        :single-line="false"
        size="small"
        :row-key="(row: BarRecord) => row.id"
        @update:sorter="handleSorterChange"
      />
      <div class="table-footer">
        <span class="info">共 {{ totalRecords }} 条记录</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, h, onMounted } from 'vue'
import { NInput, NButton, NTag, NDataTable, NDatePicker, NSelect } from 'naive-ui'
import { fetchAllBars, fetchBarSources } from '../api/bars'
import type { BarRecord } from '../api/bars'

// ============ 状态 ============
const symbol = ref('')
const loading = ref(false)
const barData = ref<BarRecord[]>([])
const totalRecords = ref(0)
const currentPage = ref(0)
const sortBy = ref('tradeDate')
const sortDir = ref<'asc' | 'desc'>('desc')

// ============ 筛选条件 ============
const filterTradeDateTs = ref<number | null>(null)
const filterSource = ref<string | null>(null)

/** 数据源选项（后端返回的 distinct source 值） */
const sourceOptions = ref<{ label: string; value: string }[]>([])

/** 是否有活跃的筛选条件 */
const hasActiveFilters = computed(() =>
  symbol.value.trim() || filterTradeDateTs.value !== null || filterSource.value !== null
)

/** 将 NDatePicker 的时间戳转为 yyyy-MM-dd 字符串 */
function tradeDateStr(): string | undefined {
  if (filterTradeDateTs.value === null) return undefined
  const d = new Date(filterTradeDateTs.value)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

// ============ 计算属性 ============
const pageTitle = computed(() => {
  const parts: string[] = []
  if (symbol.value.trim()) parts.push(symbol.value.trim().toUpperCase())
  if (filterSource.value) parts.push(filterSource.value)
  if (filterTradeDateTs.value !== null) parts.push(tradeDateStr() || '')
  const subtitle = parts.length > 0 ? parts.join(' · ') : '全量股票'
  return totalRecords.value > 0 ? `${subtitle} (${totalRecords.value}条)` : subtitle
})

// ============ 分页配置 ============
const pagination = reactive({
  page: 1,
  pageSize: 20,
  showSizePicker: true,
  pageSizes: [20, 50, 100],
  itemCount: 0,
  onChange: (page: number) => {
    pagination.page = page
    currentPage.value = page - 1
    loadAllBars()
  },
  onUpdatePageSize: (size: number) => {
    pagination.pageSize = size
    pagination.page = 1
    currentPage.value = 0
    loadAllBars()
  }
})

// ============ 表格列定义 ============
const columns = [
  { title: '股票代码', key: 'symbol', width: 100, sorter: (a: BarRecord, b: BarRecord) => a.symbol.localeCompare(b.symbol) },
  { title: '交易日', key: 'tradeDate', width: 120, defaultSortOrder: 'descend' as const, sorter: (a: BarRecord, b: BarRecord) => a.tradeDate.localeCompare(b.tradeDate) },
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

// ============ 排序处理 ============
function handleSorterChange(sorter: any) {
  if (sorter?.columnKey === 'tradeDate' && sorter?.order) {
    sortBy.value = 'tradeDate'
    sortDir.value = sorter.order === 'ascend' ? 'asc' : 'desc'
    currentPage.value = 0
    pagination.page = 1
    loadAllBars()
  }
}

// ============ 数据加载 ============
/** 加载分页数据 */
async function loadAllBars() {
  loading.value = true
  try {
    const sym = symbol.value.trim().toUpperCase() || undefined
    const date = tradeDateStr()
    const src = filterSource.value || undefined
    const res = await fetchAllBars(currentPage.value, pagination.pageSize, sortBy.value, sortDir.value, sym, date, src)
    barData.value = res.data.rows || []
    totalRecords.value = res.data.total || 0
    pagination.itemCount = totalRecords.value
  } catch (err: any) {
    barData.value = []
    totalRecords.value = 0
    pagination.itemCount = 0
  } finally {
    loading.value = false
  }
}

/** 应用筛选条件 */
function applyFilters() {
  currentPage.value = 0
  pagination.page = 1
  loadAllBars()
}

/** 重置所有筛选条件 */
function resetFilters() {
  symbol.value = ''
  filterTradeDateTs.value = null
  filterSource.value = null
  sortBy.value = 'tradeDate'
  sortDir.value = 'desc'
  currentPage.value = 0
  pagination.page = 1
  loadAllBars()
}

// ============ 初始化 ============
onMounted(async () => {
  // 加载数据源选项
  try {
    const res = await fetchBarSources()
    sourceOptions.value = (res.data.sources || []).map((s: string) => ({ label: s, value: s }))
  } catch (_) {
    sourceOptions.value = []
  }
  // 加载数据
  await loadAllBars()
})
</script>

<style scoped>
.bars-view {
  min-height: 200px;
}

.search-card {
  background: var(--bg-card);
  border-radius: 10px;
  padding: 14px 20px;
  box-shadow: var(--shadow-sm);
  margin-bottom: 20px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.filter-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  white-space: nowrap;
}

.table-container {
  background: var(--bg-card);
  border-radius: 10px;
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--border-color);
}

.table-header .title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.table-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 12px 20px;
  border-top: 1px solid var(--border-color);
}

.table-footer .info {
  font-size: 12px;
  color: var(--text-tertiary);
}
</style>
