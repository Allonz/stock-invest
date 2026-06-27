// K线数据查询 API
import request from './request'

/** K线记录 */
export interface BarRecord {
  id: number
  symbol: string
  name?: string
  tradeDate: string
  openPrice: number
  closePrice: number
  volume: number
  source: string
}

/** K线查询响应（单个股票） */
export interface BarsResponse {
  symbol: string
  total: number
  rows: BarRecord[]
}

/** 分页查询响应（全量） */
export interface BarsPageResponse {
  total: number
  totalPages: number
  page: number
  pageSize: number
  rows: BarRecord[]
}

/** 按股票代码查询K线数据 */
export function fetchBars(symbol: string) {
  return request.get<BarsResponse>('/api/bars/single/query', { params: { symbol } })
}

/** 分页查询全量K线数据（支持按股票代码/交易日/数据源筛选） */
export function fetchAllBars(
  page = 0,
  pageSize = 20,
  sortBy = 'tradeDate',
  sortDir = 'desc',
  symbol?: string,
  tradeDate?: string,
  source?: string
) {
  const params: Record<string, any> = { page, pageSize, sortBy, sortDir }
  if (symbol) params.symbol = symbol
  if (tradeDate) params.tradeDate = tradeDate
  if (source) params.source = source
  return request.get<BarsPageResponse>('/api/bars/pages/query', { params })
}

/** 获取所有数据源列表 */
export function fetchBarSources() {
  return request.get<{ sources: string[] }>('/api/bars/sources')
}
