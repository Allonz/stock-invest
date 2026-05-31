import request from './request'
import type { ApiResponse } from './request'

/** 单日开盘日历数据 */
export interface TradingCalendarDay {
  tradeDate: string    // "2026-01-05"
  isOpen: boolean
  market: string
  source: string
  type: string    // TRADING / HOLIDAY / WEEKEND
  detail: string
}

/** 全年日历抓取结果 */
export interface FetchFullYearResult {
  fetched: number
  market: string
  year: number
}

/**
 * 手动触发全年日历查询。
 * 逐天通过 fallback 链查询后 upsert 入库。
 */
export function fetchFullYear(year: number, market = 'US') {
  return request.post<ApiResponse<FetchFullYearResult>>('/api/v1/trading-calendar/fetch-full-year', null, {
    params: { year, market }
  })
}

/**
 * 获取整年日历列表（只查 DB，不触发外部调用）。
 */
export function getCalendarList(year: number, market = 'US') {
  return request.get<ApiResponse<TradingCalendarDay[]>>('/api/v1/trading-calendar/list', {
    params: { year, market }
  })
}

/**
 * 查询单日是否开盘（可用于 OpenClaw 截图导入定时任务）。
 */
export function checkIsOpen(date?: string, exchange = 'XNYS') {
  return request.get<ApiResponse<{ isOpen: boolean; source: string }>>('/api/v1/trading-calendar/is-open', {
    params: { date, exchange }
  })
}
