// 筛选相关 API
import request from './request'
import type { ApiResponse } from './request'

/** 筛选结果中的单条匹配项 */
export interface ScreeningMatch {
  id: number
  symbol: string
  lastClose: number
  rise: boolean
  windowDays: number
  algorithm: string
  name?: string          // 名称, 后端可能不返回, 前端可自行补充
  volume?: number        // 成交量
  highPrice?: number     // 最高价
  lowPrice?: number      // 最低价
  changePercent?: number // 涨跌幅（%）
  afterHours?: number | null    // 盘后价
  afterHoursChangePercent?: number | null  // 盘后涨跌幅（%）
}

/** 最新筛选通知的统计结果 */
export interface NotificationResult {
  batchId: string
  screenDate: string
  results: Record<string, Record<string, number>>
}

/** 最新筛选结果 */
export interface LatestScreening {
  tradeDate: string
  batchId: string
  totalMatches: number
  matches: ScreeningMatch[]
}

/** 筛选历史批次 */
export interface ScreeningBatch {
  lastTradeDate: string
  matchCount: number
  batchId: string
}

/** 获取最新筛选通知（顶部统计卡片用） */
export function fetchLatestNotification() {
  return request.get<ApiResponse<NotificationResult>>('/api/notification/latest')
}

/** 获取最新筛选结果列表 */
export function fetchLatestScreening() {
  return request.get<ApiResponse<LatestScreening>>('/api/screening/latest')
}

/** 获取筛选历史批次列表 */
export function fetchScreeningHistory() {
  return request.get<ApiResponse<ScreeningBatch[]>>('/api/screening/history')
}

/** 获取指定批次的详情 */
export function fetchBatchDetail(batchId: string) {
  return request.get<ApiResponse<{ batchId: string; totalMatches: number; matches: ScreeningMatch[] }>>(`/api/screening/batch/${batchId}`)
}

/** 通知历史批次 */
export interface NotificationBatch {
  batchId: string
  screenDate: string
  matchCount: number
}

/** 通知批次详情 */
export interface NotificationBatchDetail {
  batchId: string
  screenDate: string
  results: Record<string, Record<string, number>>
}

/** 获取通知历史列表 */
export function fetchNotificationHistory() {
  return request.get<ApiResponse<NotificationBatch[]>>('/api/notification/history')
}

/** 获取通知批次详情 */
export function fetchNotificationBatchDetail(batchId: string) {
  return request.get<ApiResponse<NotificationBatchDetail>>(`/api/notification/batch/${batchId}`)
}
