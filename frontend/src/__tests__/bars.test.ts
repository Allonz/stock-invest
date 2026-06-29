import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { ApiResponse } from '../api/request'
import type { CandleData } from '../api/bars'

// Mock the request module so we control get/post directly
vi.mock('../api/request', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

import request from '../api/request'
import { fetchCandles } from '../api/bars'

const mockRequestGet = request.get as ReturnType<typeof vi.fn>

describe('fetchCandles', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // FE-API-001: 成功获取 7 条 CandleData
  it('FE-API-001: should fetch candles successfully with default 7 days', async () => {
    const mockData: ApiResponse<CandleData[]> = {
      success: true,
      data: [
        { date: '2025-01-07', open: 100, high: 105, low: 99, close: 103, changePercent: 3.0, afterHours: null, afterHoursChangePercent: null, volume: 10000 },
        { date: '2025-01-08', open: 103, high: 108, low: 102, close: 107, changePercent: 3.88, afterHours: null, afterHoursChangePercent: null, volume: 12000 },
        { date: '2025-01-09', open: 107, high: 110, low: 106, close: 109, changePercent: 1.87, afterHours: 108.5, afterHoursChangePercent: -0.46, volume: 15000 },
        { date: '2025-01-10', open: 109, high: 111, low: 107, close: 108, changePercent: -0.92, afterHours: null, afterHoursChangePercent: null, volume: 9000 },
        { date: '2025-01-11', open: 108, high: 112, low: 107, close: 111, changePercent: 2.78, afterHours: 110.2, afterHoursChangePercent: -0.72, volume: 18000 },
        { date: '2025-01-12', open: 111, high: 115, low: 110, close: 114, changePercent: 2.70, afterHours: null, afterHoursChangePercent: null, volume: 22000 },
        { date: '2025-01-13', open: 114, high: 116, low: 113, close: 115, changePercent: 0.88, afterHours: 115.5, afterHoursChangePercent: 0.43, volume: 16000 },
      ],
      timestamp: '2025-01-13T10:00:00Z',
    }
    mockRequestGet.mockResolvedValue(mockData)

    const result = await fetchCandles('AAPL')
    expect(result.success).toBe(true)
    expect(result.data).toHaveLength(7)

    // Verify each candle matches CandleData interface
    result.data.forEach((candle: CandleData) => {
      expect(candle).toHaveProperty('date')
      expect(candle).toHaveProperty('open')
      expect(candle).toHaveProperty('high')
      expect(candle).toHaveProperty('low')
      expect(candle).toHaveProperty('close')
      expect(candle).toHaveProperty('changePercent')
      expect(candle).toHaveProperty('afterHours')
      expect(candle).toHaveProperty('afterHoursChangePercent')
      expect(candle).toHaveProperty('volume')
      expect(typeof candle.open).toBe('number')
      expect(typeof candle.high).toBe('number')
      expect(typeof candle.low).toBe('number')
      expect(typeof candle.close).toBe('number')
      expect(typeof candle.changePercent).toBe('number')
      expect(typeof candle.volume).toBe('number')
    })

    // Verify the correct URL was called (bars.ts: request.get<...>(`/api/bars/${symbol}/candles?days=${days}`))
    expect(mockRequestGet).toHaveBeenCalledWith('/api/bars/AAPL/candles?days=7')
  })

  // FE-API-002: 空数据
  it('FE-API-002: should handle empty candle data response', async () => {
    const mockData: ApiResponse<CandleData[]> = {
      success: true,
      data: [],
      timestamp: '2025-01-13T10:00:00Z',
    }
    mockRequestGet.mockResolvedValue(mockData)

    const result = await fetchCandles('TEST', 30)
    expect(result.success).toBe(true)
    expect(result.data).toHaveLength(0)
    expect(mockRequestGet).toHaveBeenCalledWith('/api/bars/TEST/candles?days=30')
  })

  // FE-API-003: 网络异常
  it('FE-API-003: should handle network error', async () => {
    mockRequestGet.mockRejectedValue(new Error('Network Error'))
    await expect(fetchCandles('AAPL', 7)).rejects.toThrow('Network Error')
  })

  // FE-API-004: symbol 含特殊字符
  it('FE-API-004: should handle symbol with special characters in URL encoding', async () => {
    const mockData: ApiResponse<CandleData[]> = {
      success: true,
      data: [],
      timestamp: '2025-01-13T10:00:00Z',
    }
    mockRequestGet.mockResolvedValue(mockData)

    const result = await fetchCandles('BRK.A', 7)
    expect(result.success).toBe(true)
    // The symbol appears as-is in the URL
    expect(mockRequestGet).toHaveBeenCalledWith('/api/bars/BRK.A/candles?days=7')
  })

  // FE-API-005: Real 模式标记描述（不执行）
  it.skip('FE-API-005: [REAL MODE] fetch candles with custom days parameter', () => {
    // 真实模式下，此测试验证 fetchCandles 支持自定义 days 参数
    // 例如 fetchCandles('AAPL', 30) 应请求 30 天数据
  })

  // FE-API-006: Real 模式标记描述（不执行）
  it.skip('FE-API-006: [REAL MODE] fetch candles for empty/non-existent symbol', () => {
    // 真实模式下，此测试验证不存在的 symbol 返回空数组或 404
  })
})
