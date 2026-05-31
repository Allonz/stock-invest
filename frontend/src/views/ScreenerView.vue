<template>
  <div class="screener-view">

    <!-- ===================== 区域①：触发面板（双栏） ===================== -->
    <div class="action-cards">
      <!-- 快捷筛选 -->
      <div class="action-card">
        <div class="action-icon">▶</div>
        <div class="action-content">
          <h3>快捷筛选</h3>
          <p>全量全窗口扫描（2-7天窗口），依次执行</p>
          <div class="action-status">
            <NTag v-if="quickResult" :type="quickResult.type" size="small" :bordered="false">
              {{ quickResult.message }}
            </NTag>
          </div>
        </div>
        <div class="action-btn-area">
          <NButton
            type="primary"
            :loading="quickLoading"
            :disabled="quickLoading"
            @click="handleQuickScreening"
          >
            {{ quickLoading ? '筛选中...' : '▶ 开始全量筛选' }}
          </NButton>
        </div>
      </div>

      <!-- 高级筛选 -->
      <div class="action-card">
        <div class="action-icon">⚡</div>
        <div class="action-content">
          <h3>高级筛选（自定义参数）</h3>
          <p>指定窗口天数和匹配数量上限进行筛选</p>
          <div style="display: flex; gap: 12px; margin-top: 12px; flex-wrap: wrap;">
            <div style="display: flex; align-items: center; gap: 6px;">
              <span style="font-size: 13px; color: #666;">窗口天数:</span>
              <NSelect
                v-model:value="advParams.windowDays"
                :options="windowOptions"
                style="width: 100px;"
                size="small"
              />
            </div>
            <div style="display: flex; align-items: center; gap: 6px;">
              <span style="font-size: 13px; color: #666;">数量限制:</span>
              <NInputNumber
                v-model:value="advParams.limit"
                :min="20"
                :max="500"
                :step="20"
                style="width: 120px;"
                size="small"
              />
            </div>
          </div>
          <div class="action-status">
            <NTag v-if="advResult" :type="advResult.type" size="small" :bordered="false">
              {{ advResult.message }}
            </NTag>
          </div>
        </div>
        <div class="action-btn-area">
          <NButton
            type="info"
            :loading="advLoading"
            :disabled="advLoading"
            @click="handleAdvancedScreening"
          >
            {{ advLoading ? '筛选中...' : '⚡ 开始筛选' }}
          </NButton>
        </div>
      </div>
    </div>

    <!-- ===================== 区域②：进度面板 ===================== -->
    <div v-if="showProgress" class="progress-card">
      <div class="progress-header">
        <span class="progress-title">筛选进度</span>
        <NButton size="tiny" quaternary @click="showProgress = false">关闭</NButton>
      </div>
      <div class="progress-body">
        <div v-for="wp in progressWindows" :key="wp.days" class="window-progress-row">
          <span class="window-label">{{ wp.days }}DAY</span>
          <div class="progress-bar-track">
            <div
              class="progress-bar-fill"
              :style="{ width: getProgressPercent(wp) + '%' }"
              :class="getProgressClass(wp)"
            ></div>
          </div>
          <span class="progress-text">{{ getProgressText(wp) }}{{ getProgressPercent(wp) > 0 ? ' (' + getProgressPercent(wp) + '%)' : '' }}</span>
        </div>
        <div class="progress-summary">
          总进度 {{ progressData.completedWindows }}/{{ progressData.totalWindows }} ·
          {{ progressData.elapsedSeconds }}s
        </div>
      </div>
    </div>

    <!-- ===================== 区域③：历史记录（复用 ScreeningHistory 逻辑） ===================== -->
    <div v-if="loading" class="loading-container">
      <NSpin size="large" />
      <p style="margin-top: 16px; color: #999">加载中...</p>
    </div>

    <template v-else>
      <!-- tab 切换 -->
      <div style="display: flex; gap: 8px; margin-bottom: 16px;">
        <NButton
          :type="activeTab === 'screening' ? 'primary' : 'default'"
          size="small"
          @click="switchTab('screening')"
        >📊 筛选历史({{ batches.length }})</NButton>
        <NButton
          :type="activeTab === 'notification' ? 'primary' : 'default'"
          size="small"
          @click="switchTab('notification')"
        >🔔 通知历史({{ notifBatches.length }})</NButton>
      </div>

      <!-- 筛选历史 -->
      <div v-if="activeTab === 'screening'" class="table-container">
        <div class="table-header">
          <span class="title">📊 筛选历史记录</span>
          <div class="actions">
            <NButton size="tiny" @click="loadHistory">🔄 刷新</NButton>
          </div>
        </div>

        <div v-if="batches.length === 0" class="empty-state">
          <p>暂无筛选历史记录</p>
        </div>

        <div v-for="batch in batches" :key="batch.batchId" class="batch-card">
          <div class="batch-header" @click="toggleBatch(batch.batchId)">
            <div class="batch-info">
              <span class="batch-date">{{ batch.lastTradeDate }}</span>
              <span class="batch-count">{{ batch.matchCount }} 条匹配</span>
              <span class="batch-id">ID: {{ batch.batchId.slice(0, 16) }}...</span>
            </div>
            <div class="batch-actions">
              <NButton size="tiny" quaternary>
                {{ expandedBatchId === batch.batchId ? '收起 ▲' : '展开 ▼' }}
              </NButton>
            </div>
          </div>

          <!-- 展开详情 -->
          <div v-if="expandedBatchId === batch.batchId" class="batch-detail">
            <div v-if="batchDetailLoading" style="text-align:center;padding:20px;">
              <NSpin size="small" />
            </div>
            <NDataTable
              v-else
              :columns="detailColumns"
              :data="batchDetailData"
              :bordered="false"
              :single-line="false"
              size="small"
              :max-height="400"
            />
            <div class="batch-detail-footer">
              共 {{ batchDetailData.length }} 条记录
            </div>
          </div>
        </div>

        <div class="pagination-bar">
          <span class="info">共 {{ batches.length }} 批</span>
        </div>
      </div>

      <!-- 通知历史 -->
      <div v-if="activeTab === 'notification'" class="table-container">
        <div class="table-header">
          <span class="title">🔔 通知历史记录</span>
          <div class="actions">
            <NButton size="tiny" @click="loadNotificationHistory">🔄 刷新</NButton>
          </div>
        </div>

        <div v-if="notifBatches.length === 0" class="empty-state">
          <p>暂无通知历史记录</p>
        </div>

        <div v-for="batch in notifBatches" :key="batch.batchId" class="batch-card">
          <div class="batch-header" @click="toggleNotifBatch(batch.batchId)">
            <div class="batch-info">
              <span class="batch-date">{{ batch.screenDate }}</span>
              <span class="batch-count">{{ batch.matchCount }} 条匹配</span>
              <span class="batch-id">ID: {{ batch.batchId.slice(0, 16) }}...</span>
            </div>
            <div class="batch-actions">
              <NButton size="tiny" quaternary>
                {{ notifExpandedBatchId === batch.batchId ? '收起 ▲' : '展开 ▼' }}
              </NButton>
            </div>
          </div>

          <!-- 通知详情 -->
          <div v-if="notifExpandedBatchId === batch.batchId" class="batch-detail">
            <div v-if="notifDetailLoading" style="text-align:center;padding:20px;">
              <NSpin size="small" />
            </div>
            <div v-else-if="notifDetailData" style="padding: 12px; background: #f0f7ff; border-radius: 8px; margin-bottom: 8px;">
              <span style="font-weight: 600;">批次: {{ notifDetailData.batchId }}</span>
              <span style="margin-left: 12px; color: #1890ff;">{{ notifDetailData.screenDate }}</span>
            </div>
            <div v-for="(counts, algo) in (notifDetailData?.results || {})" :key="algo" style="margin-bottom: 8px;">
              <div style="font-weight: 600; margin-bottom: 4px; color: #722ed1;">{{ algo }}</div>
              <div style="display: flex; gap: 8px; flex-wrap: wrap;">
                <NTag v-for="(count, window) in counts" :key="window" size="small" :bordered="false">
                  {{ window }}: {{ count }}条
                </NTag>
              </div>
            </div>
          </div>
        </div>

        <div class="pagination-bar">
          <span class="info">共 {{ notifBatches.length }} 批</span>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, h, onMounted } from 'vue'
import { NSpin, NButton, NDataTable, useNotification, NTag, NSelect, NInputNumber } from 'naive-ui'
import { fetchScreeningHistory, fetchBatchDetail, fetchNotificationHistory, fetchNotificationBatchDetail } from '../api/screening'
import { triggerScreeningAsync, runScreenerAsync, fetchScreeningProgress } from '../api/admin'
import type { ScreeningBatch, ScreeningMatch, NotificationBatch, NotificationBatchDetail } from '../api/screening'
import type { WindowProgress } from '../api/admin'

const notification = useNotification()

// ===================== 状态声明 =====================
// 历史
const loading = ref(true)
const batches = ref<ScreeningBatch[]>([])
const expandedBatchId = ref<string | null>(null)
const batchDetailLoading = ref(false)
const batchDetailData = ref<ScreeningMatch[]>([])

// 通知历史
const notifBatches = ref<NotificationBatch[]>([])
const notifExpandedBatchId = ref<string | null>(null)
const notifDetailLoading = ref(false)
const notifDetailData = ref<NotificationBatchDetail | null>(null)
const activeTab = ref<'screening' | 'notification'>('screening')

// 快速筛选
const quickLoading = ref(false)
const quickResult = ref<{ type: 'success' | 'error' | 'warning' | 'info'; message: string } | null>(null)
let progressTimer: ReturnType<typeof setInterval> | null = null

// 高级筛选
const advLoading = ref(false)
const advResult = ref<{ type: 'success' | 'error' | 'warning' | 'info'; message: string } | null>(null)

// 高级筛选参数
const windowOptions = [
  { label: '2天', value: 2 },
  { label: '3天', value: 3 },
  { label: '4天', value: 4 },
  { label: '5天', value: 5 },
  { label: '6天', value: 6 },
  { label: '7天', value: 7 },
]
const advParams = ref({ windowDays: 7, limit: 60 })

// 进度
const showProgress = ref(false)
const progressData = ref<{
  running: boolean
  windows: WindowProgress[]
  totalWindows: number
  completedWindows: number
  elapsedSeconds: number
  startTime: number
}>({
  running: false,
  windows: [],
  totalWindows: 0,
  completedWindows: 0,
  elapsedSeconds: 0,
  startTime: 0
})
const currentTaskId = ref<string | null>(null)

const progressWindows = ref<WindowProgress[]>([])

// ===================== Tab 切换 =====================
function switchTab(tab: 'screening' | 'notification') {
  activeTab.value = tab
}

// ===================== 进度面板 =====================
function startPollingProgress(taskId: string) {
  currentTaskId.value = taskId
  showProgress.value = true
  progressData.value.running = true
  progressData.value.windows = []
  progressData.value.totalWindows = 0
  progressData.value.completedWindows = 0
  progressData.value.elapsedSeconds = 0

  if (progressTimer) clearInterval(progressTimer)
  progressTimer = setInterval(async () => {
    try {
      const res = await fetchScreeningProgress(taskId)
      if (res.data.success && res.data.data) {
        progressData.value = res.data.data
        progressWindows.value = res.data.data.windows || []
        if (!res.data.data.running) {
          stopPollingProgress()
        }
      }
    } catch (e) {
      console.error('[Screener] progress poll error', e)
    }
  }, 1000)
}

function stopPollingProgress() {
  if (progressTimer) {
    clearInterval(progressTimer)
    progressTimer = null
  }
}

function getProgressPercent(wp: WindowProgress): number {
  if (wp.status === 'DONE') return 100
  if (wp.status === 'RUNNING') return 50
  return 0
}

function getProgressClass(wp: WindowProgress): string {
  if (wp.status === 'DONE') return 'fill-done'
  if (wp.status === 'RUNNING') return 'fill-running'
  return 'fill-waiting'
}

function getProgressText(wp: WindowProgress): string {
  if (wp.status === 'DONE') return `完成 ${wp.matched}条 ✅`
  if (wp.status === 'RUNNING') return `匹配中 ${wp.matched}条 ⏳`
  return '等待中'
}

// ===================== 快捷筛选 =====================
async function handleQuickScreening() {
  quickLoading.value = true
  quickResult.value = null
  try {
    const res = await triggerScreeningAsync()
    if (res.data.success) {
      const taskId = res.data.data?.taskId || ''
      const msg = res.data.data?.message || '全量筛选已触发'
      quickResult.value = { type: 'success', message: msg }
      notification.success({ title: '筛选已触发', content: msg, duration: 3000 })
      if (taskId) {
        startPollingProgress(taskId)
      }
    } else {
      quickResult.value = { type: 'error', message: '触发失败' }
      notification.error({ title: '触发失败', duration: 3000 })
    }
  } catch (err: any) {
    quickResult.value = { type: 'error', message: err.message || '网络错误' }
    notification.error({ title: '筛选异常', content: err.message, duration: 3000 })
  } finally {
    quickLoading.value = false
  }
}

// ===================== 高级筛选 =====================
async function handleAdvancedScreening() {
  advLoading.value = true
  advResult.value = null
  try {
    const res = await runScreenerAsync({
      limit: advParams.value.limit,
      windowDays: advParams.value.windowDays
    })
    if (res.data.success) {
      const taskId = res.data.data?.taskId || ''
      const msg = res.data.data?.message || '高级筛选已触发'
      advResult.value = { type: 'success', message: msg }
      notification.success({ title: '筛选已完成', content: msg, duration: 3000 })
      notification.success({ title: '筛选已触发', content: msg, duration: 3000 })
      if (taskId) {
        startPollingProgress(taskId)
      }
    } else {
      advResult.value = { type: 'error', message: '筛选返回失败' }
      notification.error({ title: '筛选失败', duration: 3000 })
    }
  } catch (err: any) {
    advResult.value = { type: 'error', message: err.message || '网络错误' }
    notification.error({ title: '筛选异常', content: err.message, duration: 3000 })
  } finally {
    advLoading.value = false
  }
}

// ===================== 筛选历史 =====================
// ============ 复制反馈 ============
const copiedSymbol = ref<string | null>(null)
function copySymbol(sym: string) {
  navigator.clipboard.writeText(sym)
  copiedSymbol.value = sym
  setTimeout(() => { copiedSymbol.value = null }, 1000)
}

/** 加载筛选历史 */
async function loadHistory() {
  loading.value = true
  try {
    const res = await fetchScreeningHistory()
    if (res.data.success) {
      batches.value = res.data.data
    } else {
      try { notification.error({ title: '加载失败', content: '筛选历史加载异常', duration: 3000 }) } catch (_) { }
    }
  } catch (err: any) {
    try { notification.error({ title: '网络错误', content: err.message || '请求失败', duration: 3000 }) } catch (_) { }
  } finally {
    loading.value = false
  }
}

/** 展开/收起批次详情 */
async function toggleBatch(batchId: string) {
  if (expandedBatchId.value === batchId) {
    expandedBatchId.value = null
    batchDetailData.value = []
    return
  }
  expandedBatchId.value = batchId
  batchDetailLoading.value = true
  try {
    const res = await fetchBatchDetail(batchId)
    if (res.data.success) {
      batchDetailData.value = res.data.data?.matches || res.data.data || []
    } else {
      try { notification.error({ title: '详情加载失败', duration: 3000 }) } catch (_) { }
      batchDetailData.value = []
    }
  } catch (err: any) {
    notification.error({ title: '详情加载异常', content: err.message || '请求失败', duration: 3000 })
    batchDetailData.value = []
  } finally {
    batchDetailLoading.value = false
  }
}

// ===================== 通知历史 =====================
/** 加载通知历史 */
async function loadNotificationHistory() {
  try {
    const res = await fetchNotificationHistory()
    if (res.data.success) {
      notifBatches.value = res.data.data
    }
  } catch (err: any) {
    try { notification.error({ title: '通知历史加载失败', content: err.message, duration: 3000 }) } catch (_) { }
  }
}

/** 展开/收起通知批次详情 */
async function toggleNotifBatch(batchId: string) {
  if (notifExpandedBatchId.value === batchId) {
    notifExpandedBatchId.value = null
    notifDetailData.value = null
    return
  }
  notifExpandedBatchId.value = batchId
  notifDetailLoading.value = true
  try {
    const res = await fetchNotificationBatchDetail(batchId)
    if (res.data.success) {
      notifDetailData.value = res.data.data
    } else {
      try { notification.error({ title: '通知详情加载失败', duration: 3000 }) } catch (_) { }
    }
  } catch (err: any) {
    try { notification.error({ title: '通知详情异常', content: err.message, duration: 3000 }) } catch (_) { }
  } finally {
    notifDetailLoading.value = false
  }
}

// ===================== 表格列定义 =====================
const detailColumns = [
  { title: '代码', key: 'symbol', width: 120, align: 'center' as const, render: (row: ScreeningMatch) => {
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
  { title: '收盘价', key: 'lastClose', width: 110, align: 'center' as const, render: (row: ScreeningMatch) => `${row.lastClose.toFixed(4)}` },
  { title: '涨跌', key: 'rise', width: 80, align: 'center' as const, render: (row: ScreeningMatch) => h(NTag, {
    type: row.rise ? 'error' : 'success',
    size: 'small',
    bordered: false
  }, { default: () => row.rise ? '上涨' : '下跌' }) },
  { title: '算法', key: 'algorithm', width: 140, align: 'center' as const, render: (row: ScreeningMatch) => h(NTag, {
    color: { color: '#f9f0ff', textColor: '#722ed1' },
    size: 'small',
    bordered: false
  }, { default: () => row.algorithm }) },
  { title: '窗口', key: 'windowDays', width: 70, align: 'center' as const, render: (row: ScreeningMatch) => `${row.windowDays}天` }
]

// ===================== 初始化 =====================
onMounted(() => {
  loadHistory().catch(e => console.error('[Screener] loadHistory failed:', e))
  loadNotificationHistory().catch(e => console.error('[Screener] loadNotificationHistory failed:', e))
})
</script>

<style scoped>
.screener-view {
  min-height: 200px;
}

/* === 触发面板 === */
.action-cards {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

@media (max-width: 900px) {
  .action-cards {
    grid-template-columns: 1fr;
  }
}

.action-card {
  background: #fff;
  border-radius: 10px;
  padding: 20px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
  display: flex;
  gap: 16px;
  align-items: flex-start;
  transition: box-shadow 0.2s;
}

.action-card:hover {
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
}

.action-icon {
  font-size: 32px;
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f0f7ff;
  border-radius: 12px;
  flex-shrink: 0;
}

.action-content {
  flex: 1;
}

.action-content h3 {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 6px;
  color: #333;
}

.action-content p {
  font-size: 13px;
  color: #999;
  line-height: 1.5;
}

.action-status {
  margin-top: 8px;
  min-height: 24px;
}

.action-btn-area {
  flex-shrink: 0;
  padding-top: 4px;
}

/* === 进度面板 === */
.progress-card {
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
  margin-bottom: 20px;
  overflow: hidden;
}

.progress-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid #f0f0f0;
}

.progress-title {
  font-size: 15px;
  font-weight: 600;
}

.progress-body {
  padding: 16px 20px;
}

.window-progress-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.window-label {
  font-weight: 600;
  font-size: 13px;
  color: #333;
  width: 50px;
  flex-shrink: 0;
}

.progress-bar-track {
  flex: 1;
  height: 16px;
  background: #f0f0f0;
  border-radius: 8px;
  overflow: hidden;
}

.progress-bar-fill {
  height: 100%;
  border-radius: 8px;
  transition: width 0.3s ease;
}

.progress-bar-fill.fill-done {
  background: linear-gradient(90deg, #52c41a, #73d13d);
}

.progress-bar-fill.fill-running {
  background: linear-gradient(90deg, #1890ff, #40a9ff);
}

.progress-bar-fill.fill-waiting {
  background: #d9d9d9;
}

.progress-text {
  font-size: 12px;
  color: #666;
  width: 100px;
  flex-shrink: 0;
  text-align: right;
}

.progress-summary {
  margin-top: 12px;
  text-align: center;
  font-size: 13px;
  color: #999;
}

/* === 历史记录 === */
.loading-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
}

.table-container {
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
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

.empty-state {
  padding: 40px;
  text-align: center;
  color: #999;
}

/* 批次卡片 */
.batch-card {
  border-bottom: 1px solid #f0f0f0;
}

.batch-card:last-child {
  border-bottom: none;
}

.batch-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  cursor: pointer;
  transition: background 0.2s;
}

.batch-header:hover {
  background: #f5f8ff;
}

.batch-info {
  display: flex;
  align-items: center;
  gap: 16px;
}

.batch-date {
  font-weight: 600;
  font-size: 14px;
  color: #333;
}

.batch-count {
  font-size: 13px;
  color: #1890ff;
  background: #f0f7ff;
  padding: 2px 10px;
  border-radius: 10px;
}

.batch-id {
  font-size: 12px;
  color: #999;
  font-family: 'Menlo', 'Consolas', monospace;
}

.batch-detail {
  padding: 0 20px 16px;
}

.batch-detail-footer {
  padding: 8px 0;
  font-size: 12px;
  color: #999;
  text-align: right;
}

.pagination-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 12px 20px;
  border-top: 1px solid #f0f0f0;
}

.pagination-bar .info {
  font-size: 12px;
  color: #999;
}

:deep(.symbol-text) {
  font-weight: 600;
  color: #1890ff;
}
</style>
