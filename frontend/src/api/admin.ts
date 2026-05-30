// 管理面板 API
import request from './request'
import type { ApiResponse } from './request'

/** 快速触发全量筛选（同步） */
export function triggerScreening() {
  return request.post<ApiResponse<{ message: string }>>('/api/admin/trigger-screening')
}

/** 高级筛选（同步，自定义参数） */
export function runScreener(params: {
  date?: string
  limit?: number
  windowDays?: number
}) {
  return request.post<ApiResponse<{ message: string; batchId: string }>>('/api/admin/run-screening', null, { params })
}

/** 异步全量筛选 - 立即返回 taskId */
export function triggerScreeningAsync() {
  return request.post<ApiResponse<{ taskId: string; message: string }>>('/api/admin/trigger-screening-async')
}

/** 异步高级筛选 - 立即返回 taskId */
export function runScreenerAsync(params: {
  limit?: number
  windowDays?: number
}) {
  return request.post<ApiResponse<{ taskId: string; message: string }>>('/api/admin/run-screening-async', params)
}

/** 查询异步筛选进度 */
export interface WindowProgress {
  days: number
  status: string
  matched: number
}

export interface ScreeningProgressResult {
  running: boolean
  windows: WindowProgress[]
  totalWindows: number
  completedWindows: number
  elapsedSeconds: number
  startTime: number
}

export function fetchScreeningProgress(taskId: string) {
  return request.get<ApiResponse<ScreeningProgressResult>>('/api/admin/screening-progress', { params: { taskId } })
}

/** 异步触发数据补缺 */
export function triggerDataFill() {
  return request.post<ApiResponse<{ taskId: string; message: string }>>('/api/admin/trigger-data-fill')
}

/** 获取补缺进度 */
export function fetchDataFillProgress() {
  return request.get<ApiResponse<{
    running: boolean
    stage: string
    totalSymbols: number
    processedSymbols: number
    gapsFound: number
    filled: number
    failed: number
    elapsedSeconds: number
    startTime: number
  }>>('/api/admin/data-fill-progress')
}

/** 获取补缺任务列表 */
export function fetchFillTasks(params: {
  status?: string
  page?: number
  size?: number
}) {
  return request.get<ApiResponse<{
    total: number
    page: number
    size: number
    data: Array<{
      id: number
      symbol: string
      tradeDate: string
      status: string
      retryCount: number
      maxRetries: number
      lastError: string | null
      createdAt: string | null
    }>
  }>>('/api/admin/fill-tasks', { params })
}

/** 获取补缺任务统计 */
export function fetchFillTaskCount() {
  return request.get<ApiResponse<{
    total: number
    retrying: number
    completed: number
    stopped: number
  }>>('/api/admin/fill-task-count')
}
