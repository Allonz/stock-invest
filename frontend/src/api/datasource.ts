// 数据源状态 API
import request from './request'
import type { ApiResponse } from './request'

/** 单个数据源信息 */
export interface DataSource {
  name: string
  available: boolean
  hasApiKey: boolean
  reason: string | null
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
