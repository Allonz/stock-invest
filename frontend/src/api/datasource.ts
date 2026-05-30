// 数据源状态 API
import request from './request'
import type { ApiResponse } from './request'

/** 能力标识 */
export type DataSourceCapability = 'STOCK_QUOTE' | 'TRADING_CALENDAR'

/** 单个数据源信息 */
export interface DataSource {
  name: string
  available: boolean
  hasApiKey: boolean
  reason: string | null
  capabilities: DataSourceCapability[]
}

/** 数据源状态响应 */
export interface DataSourceStatus {
  sources: DataSource[]
}

/** 获取数据源状态 */
export function fetchDataSourceStatus() {
  return request.get<ApiResponse<DataSourceStatus>>('/api/datasource/status')
}

/** 数据源健康检查详情 */
export interface DataSourceHealth {
  name: string
  available: boolean
  hasApiKey: boolean
  reason: string | null
  healthy: boolean
  capabilities: DataSourceCapability[]
}

/** 健康检查响应 */
export interface DataSourceHealthResponse {
  sources: DataSourceHealth[]
  healthyCount: number
  totalCount: number
  allHealthy: boolean
}

/** 获取数据源健康状态 */
export function fetchDataSourceHealth() {
  return request.get<ApiResponse<DataSourceHealthResponse>>('/api/datasource/health')
}

// ============ 能力标签工具函数 ============

/**
 * 将 capabilities 数组转为中文标签。
 * 映射规则：
 *   [STOCK_QUOTE]                   → '股票'
 *   [TRADING_CALENDAR]              → '日历'
 *   [STOCK_QUOTE, TRADING_CALENDAR] → '股票 · 日历'
 *   其他组合                         → 逗号分隔的英文名
 */
export function formatCapabilities(caps: DataSourceCapability[]): string {
  if (!caps || caps.length === 0) return ''
  const hasStock = caps.includes('STOCK_QUOTE')
  const hasCalendar = caps.includes('TRADING_CALENDAR')
  if (hasStock && hasCalendar) return '股票 · 日历'
  if (hasStock) return '股票'
  if (hasCalendar) return '日历'
  return caps.join(', ')
}

/** 能力标签的颜色类型 */
export function capabilityTagType(caps: DataSourceCapability[]): 'info' | 'success' | 'warning' {
  if (!caps || caps.length === 0) return 'warning'
  if (caps.includes('STOCK_QUOTE')) return 'success'
  return 'info'
}
