<template>
  <div class="fill-tasks">
    <!-- 统计卡片行 -->
    <n-grid :cols="4" :x-gap="12" :y-gap="12" style="margin-bottom: 16px;">
      <n-gi>
        <n-card title="总计" size="small" hoverable>
          <n-statistic :value="taskCount.total" />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card title="重试中" size="small" hoverable>
          <n-statistic :value="taskCount.retrying" />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card title="已完成" size="small" hoverable>
          <n-statistic :value="taskCount.completed" />
        </n-card>
      </n-gi>
      <n-gi>
        <n-card title="已停止" size="small" hoverable>
          <n-statistic :value="taskCount.stopped" />
        </n-card>
      </n-gi>
    </n-grid>

    <!-- 进度区域 -->
    <n-card title="补缺进度" size="small" style="margin-bottom: 16px;">
      <n-space vertical>
        <n-space justify="space-between" align="center">
          <n-space>
            <n-tag :type="progressStageTagType" size="small" :bordered="false">
              {{ progressStageLabel }}
            </n-tag>
            <span v-if="progress.running" class="progress-time">已耗时 {{ progress.elapsedSeconds }}秒</span>
            <span v-else-if="progress.stage === 'COMPLETED'" class="progress-complete">已完成</span>
          </n-space>
          <n-button
            type="warning"
            size="small"
            :loading="triggerLoading"
            :disabled="triggerLoading || progress.running"
            @click="handleTriggerFill"
          >
            触发补缺
          </n-button>
        </n-space>

        <!-- 4阶段进度展示（按规划图样式） -->
        <div class="stages-container">
          <div
            v-for="(stage, idx) in stages"
            :key="idx"
            class="stage-block"
            :class="{ active: stage.active, done: stage.done, waiting: !stage.active && !stage.done }"
          >
            <div class="stage-header">
              <div class="stage-icon-box">
                <span v-if="stage.done" class="stage-icon">✓</span>
                <span v-else-if="stage.active" class="stage-icon spin">⏳</span>
                <span v-else class="stage-icon">○</span>
              </div>
              <div class="stage-title">阶段 {{ idx + 1 }}：{{ stage.label }}</div>
              <span v-if="stage.done" class="stage-status done-text">完成</span>
              <span v-else-if="stage.active" class="stage-status active-text">进行中</span>
              <span v-else class="stage-status waiting-text">待开始</span>
            </div>
            <div class="stage-detail">{{ stage.detail }}</div>
            <!-- 阶段3补填数据时显示进度条 -->
            <div v-if="stage.label === '补填数据' && stage.active" class="stage-progress-bar">
              <div class="progress-bar-bg">
                <div class="progress-bar-fill" :style="{ width: fillPercent + '%' }"></div>
              </div>
              <span class="stage-pct">{{ fillPercent }}%</span>
            </div>
          </div>
        </div>
      </n-space>
    </n-card>

    <!-- 任务列表 -->
    <n-card title="补缺任务列表" size="small">
      <n-space vertical>
        <n-space justify="space-between" align="center">
          <n-space>
            <n-input
              v-model:value="filterSymbol"
              placeholder="代码"
              style="width: 130px;"
              size="small"
              clearable
              @keyup.enter="applyFilters"
              @clear="applyFilters"
            />
            <n-date-picker
              v-model:value="filterTradeDateTs"
              type="date"
              placeholder="交易日期"
              style="width: 150px;"
              size="small"
              clearable
              @update:value="applyFilters"
            />
            <n-select
              v-model:value="filterStatus"
              :options="statusOptions"
              placeholder="全部状态"
              style="width: 130px;"
              size="small"
              clearable
              @update:value="applyFilters"
            />
            <n-button size="small" type="primary" @click="applyFilters">搜索</n-button>
          </n-space>
          <n-button size="tiny" @click="handleRefresh">刷新</n-button>
        </n-space>

        <n-data-table
          :columns="columns"
          :data="taskList"
          :bordered="false"
          :single-line="false"
          size="small"
          :loading="tableLoading"
          :sort-state="sortState"
          @update:sorter="handleSorterChange"
        />
        <n-space justify="end" style="margin-top: 12px">
          <n-pagination
            v-model:page="pagination.page"
            v-model:page-size="pagination.pageSize"
            :item-count="pagination.itemCount"
            :page-sizes="[10, 20, 50, 100]"
            show-size-picker
            @update:page="onPageChange"
            @update:page-size="onPageSizeChange"
          />
        </n-space>
      </n-space>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, h, nextTick } from 'vue'
import {
  NButton, NDataTable, NTag, NProgress,
  NCard, NSpace, NGrid, NGi, NStatistic, NSelect, NEmpty,
  NPagination, NInput, NDatePicker,
  useNotification
} from 'naive-ui'
import { triggerDataFill, triggerRetryTasks, fetchDataFillProgress, fetchFillTasks, fetchFillTaskCount } from '../api/admin'

const notification = useNotification()

// ============ 统计数据 ============
const taskCount = reactive({
  total: 0,
  retrying: 0,
  completed: 0,
  stopped: 0
})

// ============ 进度 ============
const progress = reactive({
  running: false,
  stage: 'IDLE',
  totalSymbols: 0,
  processedSymbols: 0,
  gapsFound: 0,
  filled: 0,
  failed: 0,
  elapsedSeconds: 0,
  startTime: 0
})

const triggerLoading = ref(false)
const retryLoading = ref(false)

// 阶段定义（按规划图，增加详情文字）
const stages = computed(() => [
  {
    label: '接收任务',
    active: progress.stage === 'SCANNING' || progress.stage === 'FILLING',
    done: progress.stage !== 'IDLE',
    detail: progress.stage !== 'IDLE' ? '后台已收到补缺请求' : '等待触发'
  },
  {
    label: '计算补缺任务',
    active: progress.stage === 'SCANNING',
    done: progress.stage === 'FILLING' || progress.stage === 'COMPLETED',
    detail: progress.stage === 'SCANNING' ? `正在扫描 ${progress.processedSymbols}/${progress.totalSymbols} 只股票` :
           (progress.stage === 'FILLING' || progress.stage === 'COMPLETED') ? `共扫描 ${progress.totalSymbols} 只股票，发现 ${progress.gapsFound} 个缺失日期` : '等待中'
  },
  {
    label: '补填数据',
    active: progress.stage === 'FILLING',
    done: progress.stage === 'COMPLETED',
    detail: progress.stage === 'FILLING' ? `已完成 ${progress.filled}/${progress.gapsFound}，成功 ${progress.filled}，失败 ${progress.failed}` :
           progress.stage === 'COMPLETED' ? `补填完成，成功 ${progress.filled}，失败 ${progress.failed}` : '等待中'
  },
  {
    label: '完成',
    active: progress.stage === 'COMPLETED' && !progress.running,
    done: progress.stage === 'COMPLETED',
    detail: progress.stage === 'COMPLETED' && !progress.running ? '补缺任务已完成' : '等待中'
  }
])

const connectorPercent = computed(() => {
  if (progress.stage === 'IDLE') return 0
  if (progress.stage === 'SCANNING') return 25
  if (progress.stage === 'FILLING') return 50
  if (progress.stage === 'COMPLETED' && !progress.running) return 100
  return 75
})

const progressStageLabel = computed(() => {
  const labelMap: Record<string, string> = {
    IDLE: '空闲',
    SCANNING: '扫描中',
    FILLING: '补填中',
    COMPLETED: '完成'
  }
  return labelMap[progress.stage] || progress.stage
})

const progressStageTagType = computed(() => {
  const typeMap: Record<string, 'default' | 'info' | 'success' | 'warning' | 'error'> = {
    IDLE: 'default',
    SCANNING: 'info',
    FILLING: 'warning',
    COMPLETED: 'success'
  }
  return typeMap[progress.stage] || 'default'
})

const fillPercent = computed(() => {
  if (progress.gapsFound === 0) return 0
  return Math.round((progress.filled / progress.gapsFound) * 100)
})

// ============ 任务列表 ============
const tableLoading = ref(false)
const filterStatus = ref<string | null>(null)

const statusOptions = [
  { label: '全部状态', value: null as any },
  { label: '重试中', value: 'retrying' },
  { label: '已完成', value: 'completed' },
  { label: '已停止', value: 'stopped' }
]

interface TaskRecord {
  id: number
  symbol: string
  tradeDate: string
  status: string
  retryCount: number
  maxRetries: number
  lastError: string | null
  createdAt: string | null
}

const taskList = ref<TaskRecord[]>([])

const filterSymbol = ref<string>('')
const filterTradeDateTs = ref<number | null>(null)
const sortBy = ref<string | null>(null)
const sortOrder = ref<'asc' | 'desc'>('desc')

const sortState = computed(() => {
  if (!sortBy.value) return null
  return [{ columnKey: sortBy.value, order: sortOrder.value }]
})

const pagination = reactive({
  page: 1,
  pageSize: 20,
  itemCount: 0,
})

function onPageChange(page: number) {
  pagination.page = page
  loadTasks()
}

function onPageSizeChange(size: number) {
  pagination.pageSize = size
  pagination.page = 1
  loadTasks()
}

const columns = [
  { title: 'ID', key: 'id', width: 70, align: 'center' as const },
  { title: '代码', key: 'symbol', width: 120, align: 'center' as const, sorter: 'default' as const, render: (row: TaskRecord) => {
        const copied = copiedSymbol.value === row.symbol
        return [
          h('span', { class: 'symbol-text' }, row.symbol),
          copied
            ? h('span', { style: 'color:#52c41a;font-size:12px;margin-left:24px' }, '✓ 复制成功')
            : h('a', {
          style: 'margin-left:24px;cursor:pointer;color:#1890ff;font-size:12px',
          onClick: () => { copySymbol(row.symbol); return false; }
        }, '复制')
        ]
      } },
  { title: '交易日', key: 'tradeDate', width: 110, align: 'center' as const, sorter: 'default' as const },
  {
    title: '状态', key: 'status', width: 90, align: 'center' as const,
    render: (row: TaskRecord) => {
      const map: Record<string, { type: string; label: string }> = {
        retrying: { type: 'warning', label: '重试中' },
        completed: { type: 'success', label: '已完成' },
        stopped: { type: 'default', label: '已停止' },
        pending: { type: 'info', label: '待处理' }
      }
      const s = map[row.status] || { type: 'default', label: row.status }
      return h(NTag, { type: s.type as any, size: 'small', bordered: false }, { default: () => s.label })
    }
  },
  { title: '重试次数', key: 'retryCount', width: 90, align: 'center' as const },
  { title: '最大重试', key: 'maxRetries', width: 90, align: 'center' as const },
  {
    title: '错误信息', key: 'lastError', width: 200, align: 'center' as const,
    ellipsis: { tooltip: true }
  },
  { title: '创建时间', key: 'createdAt', width: 170, align: 'center' as const }
]

// ============ 复制反馈 ============
const copiedSymbol = ref<string | null>(null)
function copySymbol(sym: string) {
  navigator.clipboard.writeText(sym)
  copiedSymbol.value = sym
  setTimeout(() => { copiedSymbol.value = null }, 1000)
}

// ============ 数据加载 ============
async function loadTaskCount() {
  try {
    const res = await fetchFillTaskCount()
    if (res.data.success && res.data.data) {
      taskCount.total = res.data.data.total
      taskCount.retrying = res.data.data.retrying
      taskCount.completed = res.data.data.completed
      taskCount.stopped = res.data.data.stopped
    }
  } catch (e) {
    // ignore
  }
}

async function loadTasks() {
  tableLoading.value = true
  try {
    const params: any = {
      page: pagination.page,
      size: pagination.pageSize
    }
    if (filterStatus.value) {
      params.status = filterStatus.value
    }
    if (filterSymbol.value) {
      params.symbol = filterSymbol.value
    }
    if (filterTradeDateTs.value) {
      const d = new Date(filterTradeDateTs.value)
      params.tradeDate = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0')
    }
    if (sortBy.value) {
      params.sortBy = sortBy.value
      params.sortOrder = sortOrder.value
    }
    const res = await fetchFillTasks(params)
    if (res.data.success && res.data.data) {
      taskList.value = res.data.data.data
      pagination.itemCount = res.data.data.total
    }
  } catch (e) {
    // ignore
  } finally {
    tableLoading.value = false
  }
}

async function loadProgress() {
  try {
    const res = await fetchDataFillProgress()
    if (res.data.success && res.data.data) {
      const p = res.data.data
      progress.running = p.running
      progress.stage = p.stage
      progress.totalSymbols = p.totalSymbols
      progress.processedSymbols = p.processedSymbols
      progress.gapsFound = p.gapsFound
      progress.filled = p.filled
      progress.failed = p.failed
      progress.elapsedSeconds = p.elapsedSeconds
      progress.startTime = p.startTime
    }
  } catch (e) {
    // ignore
  }
}

// ============ 操作 ============

async function handleTriggerRetry() {
  retryLoading.value = true
  try {
    const res = await triggerRetryTasks()
    if (res.data.success) {
      notification.success({ title: '触发历史重试', content: '历史任务重试已异步启动', duration: 3000 })
    } else {
      notification.error({ title: '触发失败', content: res.data.data?.message || '未知错误', duration: 3000 })
    }
  } catch (err: any) {
    notification.error({ title: '触发失败', content: err.message || '网络错误', duration: 3000 })
  } finally {
    retryLoading.value = false
  }
}

async function handleTriggerFill() {
  triggerLoading.value = true
  try {
    const res = await triggerDataFill()
    if (res.data.success) {
      notification.success({ title: '触发补缺', content: '补缺任务已异步启动', duration: 3000 })
      // 立即加载进度，开始轮询
      await loadProgress()
      startPolling()
    } else {
      notification.error({ title: '触发失败', content: res.data.data?.message || '未知错误', duration: 3000 })
    }
  } catch (err: any) {
    notification.error({ title: '触发失败', content: err.message || '网络错误', duration: 3000 })
  } finally {
    triggerLoading.value = false
  }
}

function handleSorterChange(sorter: any) {
  if (sorter && sorter.columnKey && sorter.order) {
    sortBy.value = sorter.columnKey
    sortOrder.value = sorter.order
  } else {
    sortBy.value = null
    sortOrder.value = 'desc'
  }
  pagination.page = 1
  loadTasks()
}

// 统一触发筛选：重置分页+排序，重新加载
function applyFilters() {
  console.log('[FillTasks] applyFilters symbol=' + filterSymbol.value + ' date=' + filterTradeDateTs.value + ' status=' + filterStatus.value)
  pagination.page = 1
  sortBy.value = null
  sortOrder.value = 'desc'
  nextTick(() => { loadTasks() })
}

function handleRefresh() {
  pagination.page = 1
  loadTasks()
  loadTaskCount()
}

// ============ 轮询 ============
let pollingTimer: ReturnType<typeof setInterval> | null = null

function startPolling() {
  stopPolling()
  pollingTimer = setInterval(async () => {
    await loadProgress()
    // 如果补缺已完成或空闲，停止轮询
    if (!progress.running || progress.stage === 'COMPLETED') {
      stopPolling()
      // 完成后刷新统计数据
      loadTaskCount()
      loadTasks()
    }
  }, 2000)
}

function stopPolling() {
  if (pollingTimer !== null) {
    clearInterval(pollingTimer)
    pollingTimer = null
  }
}

onMounted(() => {
  loadTaskCount()
  loadProgress()
  loadTasks()
  // 如果有正在运行的补缺，开始轮询
  if (progress.running) {
    startPolling()
  }
})

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.fill-tasks {
  min-height: 200px;
}

.symbol-text {
  font-weight: 600;
  color: #1890ff;
}

.progress-time {
  color: var(--text-tertiary);
  font-size: 12px;
}

.progress-complete {
  color: #18a058;
  font-size: 12px;
  font-weight: 600;
}

.stages-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 8px 0;
}

.stage-block {
  display: flex;
  flex-direction: column;
  padding: 10px 16px;
  border-radius: 8px;
  background: var(--stage-bg);
  border: 1px solid transparent;
  transition: all 0.3s;
}

.stage-block.waiting {
  background: var(--stage-bg);
}

.stage-block.active {
  background: var(--accent-bg);
  border: 1px solid #2a4a7a;
}

.stage-block.done {
  background: #1a2e1a;
  border: 1px solid #2a5a2a;
}

.stage-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.stage-icon-box {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  flex-shrink: 0;
}

.stage-block.waiting .stage-icon-box {
  background: var(--hover-bg);
  color: var(--text-tertiary);
}

.stage-block.active .stage-icon-box {
  background: #1890ff;
  color: #fff;
}

.stage-block.done .stage-icon-box {
  background: #52c41a;
  color: #fff;
}

.stage-icon.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.stage-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}

.stage-block.active .stage-title {
  color: #1890ff;
}

.stage-block.done .stage-title {
  color: #52c41a;
}

.stage-status {
  font-size: 12px;
  margin-left: auto;
}

.done-text {
  color: #52c41a;
}

.active-text {
  color: #1890ff;
}

.waiting-text {
  color: var(--text-tertiary);
}

.stage-detail {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 4px;
  margin-left: 44px;
}

.stage-block.active .stage-detail {
  color: #1890ff;
}

.stage-block.done .stage-detail {
  color: #52c41a;
}

.stage-progress-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  margin-left: 44px;
}

.progress-bar-bg {
  width: 200px;
  height: 8px;
  background: var(--progress-bg);
  border-radius: 4px;
  overflow: hidden;
}

.progress-bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #1890ff, #36cfc9);
  border-radius: 4px;
  transition: width 0.5s;
}

.stage-pct {
  font-size: 13px;
  font-weight: 600;
  color: #1890ff;
  min-width: 40px;
  text-align: right;
}

.fill-progress {
  margin-top: 8px;
}

.fill-progress-header {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 4px;
}

.fill-progress-no-data {
  padding: 16px 0;
}
</style>
