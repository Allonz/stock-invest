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

/** K线查询响应 */
export interface BarsResponse {
  symbol: string
  total: number
  rows: BarRecord[]
}

/** 按股票代码查询K线数据 */
export function fetchBars(symbol: string) {
  return request.get<BarsResponse>('/api/bars/single/query', { params: { symbol } })
}
