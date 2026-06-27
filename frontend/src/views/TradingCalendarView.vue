<template>
  <div class="trading-calendar-view">
    <!-- 加载中状态 -->
    <div v-if="loading" class="loading-container">
      <NSpin size="large" />
      <p style="margin-top: 16px; color: #999">加载开盘日历数据...</p>
    </div>

    <template v-else>
      <!-- 顶部操作栏 -->
      <div class="toolbar-cards">
        <div class="action-card">
          <div class="action-icon">📅</div>
          <div class="action-content">
            <h3>美股开盘日历</h3>
            <p>查询全年开盘日历并缓存至数据库</p>
            <div class="action-status" v-if="fetchStatus">
              <NTag v-if="fetchLoading" type="warning" size="small" :bordered="false">⏳ 查询中...</NTag>
              <NTag v-else-if="fetchDone" type="success" size="small" :bordered="false">
                ✅ {{ fetchStatus }}
              </NTag>
              <NTag v-else size="small" :bordered="false">⏹ 空闲</NTag>
            </div>
          </div>
          <div class="action-btn-area">
            <NButton
              type="primary"
              :loading="fetchLoading"
              :disabled="fetchLoading"
              @click="handleFetchFullYear"
            >
              {{ fetchLoading ? '查询中...' : '📥 查询全年开盘日历' }}
            </NButton>
          </div>
        </div>
      </div>

      <!-- 年份 & 市场选择 -->
      <div class="filter-bar">
        <div class="filter-item">
          <span class="filter-label">年份</span>
          <NSelect
            v-model:value="selectedYear"
            :options="yearOptions"
            style="width: 120px"
            @update:value="loadCalendarData"
          />
        </div>
        <div class="filter-item">
          <span class="filter-label">市场</span>
          <NSelect
            v-model:value="selectedMarket"
            :options="marketOptions"
            style="width: 120px"
            disabled
          />
        </div>

        <!-- 月份切换 -->
        <div class="month-tabs">
          <NButton
            v-for="m in 12"
            :key="m"
            size="tiny"
            :type="currentMonth === m ? 'primary' : 'default'"
            @click="currentMonth = m"
            style="min-width: 44px"
          >
            {{ m }}月
          </NButton>
        </div>
      </div>

      <!-- 日历主体 -->
      <div class="calendar-container" v-if="currentMonthDays.length > 0">
        <!-- 图例 -->
        <div class="legend">
          <span class="legend-item"><span class="dot dot-open"></span> 开盘日</span>
          <span class="legend-item"><span class="dot dot-closed"></span> 休市日</span>
          <span class="legend-item"><span class="dot dot-holiday"></span> 节假日</span>
          <span class="legend-item"><span class="dot dot-weekend"></span> 周末</span>
        </div>

        <div class="calendar-header">
          <span v-for="day in ['日', '一', '二', '三', '四', '五', '六']" :key="day" class="calendar-weekday">
            {{ day }}
          </span>
        </div>

        <div class="calendar-grid">
          <!-- 月首偏移 -->
          <div
            v-for="i in monthStartOffset"
            :key="'empty-' + i"
            class="calendar-cell empty"
          ></div>

          <!-- 每日格子 -->
          <div
            v-for="day in currentMonthDays"
            :key="day.tradeDate"
            class="calendar-cell"
            :class="dayCellClass(day)"
            :title="dayTooltip(day)"
          >
            <span class="day-number">{{ day.dayOfMonth }}</span>
            <span v-if="day.isHoliday" class="day-marker">休</span>
          </div>
        </div>

        <!-- 统计 -->
        <div class="calendar-stats">
          <span>{{ selectedYear }}年 {{ currentMonth }}月</span>
          <span>总天数: <strong>{{ currentMonthDays.length }}</strong></span>
          <span>开盘日: <strong class="stat-open">{{ monthStats.openDays }}</strong></span>
          <span>休市日: <strong class="stat-closed">{{ monthStats.closedDays }}</strong></span>
          <span v-if="monthStats.holidayDays > 0">
            节假日: <strong class="stat-holiday">{{ monthStats.holidayDays }}</strong>
          </span>
        </div>
      </div>

      <!-- 空数据提示 -->
      <div v-else-if="!loading" class="empty-state">
        <div class="empty-icon">📅</div>
        <p>暂无日历数据</p>
        <p style="color: #999; font-size: 13px">请先点击「查询全年开盘日历」按钮获取数据</p>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { NSpin, NButton, NTag, NSelect } from 'naive-ui'
import { fetchFullYear, getCalendarList } from '../api/tradingCalendar'
import type { TradingCalendarDay } from '../api/tradingCalendar'

// ---- 状态 ----
const loading = ref(true)
const fetchLoading = ref(false)
const fetchDone = ref(false)
const fetchStatus = ref<string>('')

const selectedYear = ref(new Date().getFullYear())
const selectedMarket = ref('US')
const currentMonth = ref(new Date().getMonth() + 1)

const calendarData = ref<TradingCalendarDay[]>([])

const yearOptions = computed(() => {
  const current = new Date().getFullYear()
  const years = []
  for (let y = current - 1; y <= current + 1; y++) {
    years.push({ label: `${y}年`, value: y })
  }
  return years
})

const marketOptions = [
  { label: 'US (美股)', value: 'US' }
]

// ---- 月度数据计算 ----

const monthStartOffset = computed(() => {
  // 当月第一天是星期几（0=周日，1=周一...）
  const firstDay = new Date(selectedYear.value, currentMonth.value - 1, 1).getDay()
  return firstDay === 0 ? 0 : firstDay
})

interface CalendarDayInfo {
  tradeDate: string
  dayOfMonth: number
  isOpen: boolean
  isHoliday: boolean
  type: string
  detail: string | null
  date: Date
}

const currentMonthDays = computed(() => {
  const year = selectedYear.value
  const month = currentMonth.value
  const daysInMonth = new Date(year, month, 0).getDate()

  const days: CalendarDayInfo[] = []
  const monthData = calendarData.value.filter(d => {
    const date = new Date(d.tradeDate)
    return date.getFullYear() === year && date.getMonth() + 1 === month
  })

  // Build a map for quick lookup
  const dataMap = new Map<string, TradingCalendarDay>()
  for (const d of monthData) {
    dataMap.set(d.tradeDate, d)
  }

  for (let day = 1; day <= daysInMonth; day++) {
    const dateStr = `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
    const d = new Date(year, month - 1, day)
    const dayOfWeek = d.getDay()

    const record = dataMap.get(dateStr)
    const isWeekend = dayOfWeek === 0 || dayOfWeek === 6

    days.push({
      tradeDate: dateStr,
      dayOfMonth: day,
      isOpen: record ? record.isOpen : false,
      isHoliday: record ? record.type === 'HOLIDAY' || record.type === 'WEEKEND' : isWeekend,
      type: record ? record.type : (isWeekend ? 'WEEKEND' : 'UNKNOWN'),
      detail: record ? record.detail : null,
      date: d
    })
  }

  return days
})

const dayCellClass = (day: CalendarDayInfo) => {
  if (day.isHoliday || (!day.isOpen && day.type === 'WEEKEND')) return 'cell-weekend'
  if (!day.isOpen) return 'cell-closed'
  // 有 detail 说明是已确认的开盘日
  if (day.type === 'TRADING') return 'cell-open'
  // 未知但非周末（DB 无数据时）
  return 'cell-unknown'
}

const dayTooltip = (day: CalendarDayInfo) => {
  let info = `${day.tradeDate}`
  if (day.isOpen && day.type === 'TRADING') {
    info += '  🟢 开盘日'
  } else if (day.detail) {
    info += `  🔴 ${day.detail}`
  } else if (day.type === 'WEEKEND') {
    info += '  ⚪ 周末'
  } else if (!day.isOpen) {
    info += '  🔴 休市'
  }
  return info
}

const monthStats = computed(() => {
  let openDays = 0, closedDays = 0, holidayDays = 0
  for (const day of currentMonthDays.value) {
    if (day.isOpen && day.type === 'TRADING') {
      openDays++
    } else if (day.type === 'HOLIDAY') {
      closedDays++
      holidayDays++
    } else if (day.type === 'WEEKEND') {
      closedDays++
    } else if (!day.isOpen) {
      closedDays++
    }
  }
  return { openDays, closedDays, holidayDays }
})

// ---- 方法 ----

async function handleFetchFullYear() {
  fetchLoading.value = true
  fetchDone.value = false
  fetchStatus.value = ''

  try {
    const resp = await fetchFullYear(selectedYear.value, selectedMarket.value)
    const data = resp.data
    if (resp.status === 200 && data.success) {
      fetchDone.value = true
      fetchStatus.value = `成功获取 ${data.data.fetched} 天日历数据`
      await loadCalendarData()
    } else {
      const errMsg = (data as any).message || '未知错误'
      fetchStatus.value = '获取失败: ' + errMsg
    }
  } catch (e: any) {
    fetchStatus.value = '请求失败: ' + (e.message || '网络错误')
  } finally {
    fetchLoading.value = false
  }
}

async function loadCalendarData() {
  loading.value = true
  try {
    const resp = await getCalendarList(selectedYear.value, selectedMarket.value)
    const data = resp.data
    if (resp.status === 200 && data.success) {
      calendarData.value = data.data || []
    } else {
      calendarData.value = []
    }
  } catch {
    calendarData.value = []
  } finally {
    loading.value = false
  }
}

// ---- 初始化 ----
onMounted(() => {
  loadCalendarData()
})
</script>

<style scoped>
.trading-calendar-view {
  padding: 0;
}

.loading-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 300px;
}

/* ========= 顶部操作栏 ========= */
.toolbar-cards {
  margin-bottom: 16px;
}

.action-card {
  background: var(--bg-card);
  border-radius: 10px;
  padding: 16px 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: var(--shadow-sm);
}

.action-icon {
  font-size: 28px;
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--icon-bg);
  border-radius: 10px;
  flex-shrink: 0;
}

.action-content {
  flex: 1;
}

.action-content h3 {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.action-content p {
  margin: 0;
  font-size: 13px;
  color: var(--text-tertiary);
}

.action-status {
  margin-top: 8px;
}

.action-btn-area {
  flex-shrink: 0;
}

/* ========= 过滤栏 ========= */
.filter-bar {
  background: var(--bg-card);
  border-radius: 10px;
  padding: 12px 16px;
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
}

.filter-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-label {
  font-size: 13px;
  color: var(--text-secondary);
  white-space: nowrap;
}

.month-tabs {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

/* ========= 图例 ========= */
.legend {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
  padding: 8px 12px;
  background: var(--legend-bg);
  border-radius: 6px;
  font-size: 12px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--text-secondary);
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}

.dot-open { background: #52c41a; }
.dot-closed { background: #ff4d4f; }
.dot-holiday { background: #fa8c16; }
.dot-weekend { background: #3a3a40; }

/* ========= 日历网格 ========= */
.calendar-container {
  background: var(--bg-card);
  border-radius: 10px;
  padding: 16px 20px;
  box-shadow: var(--shadow-sm);
}

.calendar-header {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  margin-bottom: 4px;
}

.calendar-weekday {
  text-align: center;
  font-size: 12px;
  color: var(--text-tertiary);
  padding: 6px 0;
  font-weight: 500;
}

.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 2px;
}

.calendar-cell {
  aspect-ratio: 1.2;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  cursor: default;
  position: relative;
  min-height: 50px;
  font-size: 14px;
  transition: background 0.15s;
}

.calendar-cell:hover {
  opacity: 0.85;
}

.calendar-cell.empty {
  background: transparent;
  cursor: default;
}

.calendar-cell.cell-open {
  background: #1a2e1a;
  color: #52c41a;
  border: 1px solid #2a5a2a;
}

.calendar-cell.cell-closed {
  background: #2e1a1a;
  color: #ff4d4f;
  border: 1px solid #5a2a2a;
}

.calendar-cell.cell-weekend {
  background: var(--stage-bg);
  color: #3a3a40;
  border: 1px solid transparent;
}

.calendar-cell.cell-holiday {
  background: #2e2418;
  color: #fa8c16;
  border: 1px solid #5a3a18;
}

.calendar-cell.cell-unknown {
  background: var(--hover-bg);
  color: var(--text-tertiary);
  border: 1px solid transparent;
}

.day-number {
  font-weight: 500;
}

.day-marker {
  font-size: 10px;
  font-weight: 600;
  margin-top: 2px;
  padding: 0 4px;
  border-radius: 3px;
  background: rgba(250, 140, 22, 0.2);
  color: #fa8c16;
}

/* ========= 统计 ========= */
.calendar-stats {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--border-color);
  display: flex;
  gap: 20px;
  font-size: 13px;
  color: var(--text-secondary);
  flex-wrap: wrap;
}

.stat-open { color: #52c41a; }
.stat-closed { color: #ff4d4f; }
.stat-holiday { color: #fa8c16; }

/* ========= 空状态 ========= */
.empty-state {
  text-align: center;
  padding: 80px 20px;
  background: var(--bg-card);
  border-radius: 10px;
  box-shadow: var(--shadow-sm);
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 16px;
}
</style>
