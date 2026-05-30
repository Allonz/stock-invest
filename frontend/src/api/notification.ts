// 通知相关 API
import request from './request'
import type { ApiResponse } from './request'
import type { NotificationResult } from './screening'

/** 获取最新筛选通知 */
export function fetchLatestNotification() {
  return request.get<ApiResponse<NotificationResult>>('/api/notification/latest')
}
