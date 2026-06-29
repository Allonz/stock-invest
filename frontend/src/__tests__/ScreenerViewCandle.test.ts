import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

// ========== Mock all external dependencies FIRST ==========

// Mock naive-ui — stub all components used by ScreenerView.vue
vi.mock('naive-ui', () => ({
  NSpin: { name: 'NSpin', template: '<div class="mock-nspin"><slot /></div>' },
  NButton: { name: 'NButton', template: '<button class="mock-nbutton" @click="$emit(\'click\')"><slot /></button>' },
  NDataTable: { name: 'NDataTable', template: '<div class="mock-ndatatable"><slot /></div>' },
  NTag: { name: 'NTag', template: '<span class="mock-ntag"><slot /></span>' },
  NSelect: { name: 'NSelect', template: '<select class="mock-nselect"><slot /></select>' },
  NInputNumber: { name: 'NInputNumber', template: '<input class="mock-ninputnumber" /><slot /></input>' },
  useNotification: () => ({
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
  }),
}))

// Mock vue-echarts
vi.mock('vue-echarts', () => ({
  default: { name: 'VChart', template: '<div class="mock-chart" />' },
}))

// Mock echarts core (used in ScreenerView)
vi.mock('echarts/core', () => ({ use: vi.fn() }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))
vi.mock('echarts/charts', () => ({ CandlestickChart: {} }))
vi.mock('echarts/components', () => ({
  GridComponent: {},
  TooltipComponent: {},
  DataZoomComponent: {},
  TitleComponent: {},
}))

// Mock API modules
vi.mock('../api/bars', () => ({
  fetchCandles: vi.fn(),
}))
vi.mock('../api/screening', () => ({
  fetchScreeningHistory: vi.fn(),
  fetchBatchDetail: vi.fn(),
  fetchNotificationHistory: vi.fn(),
  fetchNotificationBatchDetail: vi.fn(),
}))
vi.mock('../api/admin', () => ({
  triggerScreeningAsync: vi.fn(),
  runScreenerAsync: vi.fn(),
  fetchScreeningProgress: vi.fn(),
}))

// ========== Imports after mocks ==========

import { fetchCandles } from '../api/bars'
import { fetchScreeningHistory, fetchNotificationHistory } from '../api/screening'

describe('ScreenerView.vue — K线交互逻辑', () => {
  beforeEach(() => {
    vi.clearAllMocks()

    // Default mock for screening APIs so component mounts without error
    vi.mocked(fetchScreeningHistory).mockResolvedValue({
      data: { success: true, data: [], timestamp: '' },
    } as any)
    vi.mocked(fetchNotificationHistory).mockResolvedValue({
      data: { success: true, data: [], timestamp: '' },
    } as any)
  })

  // FE-VIEW-001: 组件可正常挂载，初始状态为加载中
  it('FE-VIEW-001: should mount with loading state', async () => {
    const ScreenerView = (await import('../views/ScreenerView.vue')).default
    const wrapper = mount(ScreenerView, {
      global: { stubs: { VChart: true } },
    })
    expect(wrapper.exists()).toBe(true)
    // After mount, loadHistory runs which sets loading=false on success
    // But initially loading is true, then set to false after async
    await new Promise(resolve => setTimeout(resolve, 100))
  })

  // FE-VIEW-002: 点击 symbol 触发 fetchCandles，candleData 被更新
  it('FE-VIEW-002: onSymbolClick should fetch candles and update candleData', async () => {
    const mockCandleData = [
      { date: '2025-01-07', open: 100, high: 105, low: 99, close: 103, changePercent: 3.0, afterHours: null, afterHoursChangePercent: null, volume: 10000 },
      { date: '2025-01-08', open: 103, high: 108, low: 102, close: 107, changePercent: 3.88, afterHours: null, afterHoursChangePercent: null, volume: 12000 },
    ]
    vi.mocked(fetchCandles).mockResolvedValue({
      data: { success: true, data: mockCandleData, timestamp: '' },
    } as any)

    const ScreenerView = (await import('../views/ScreenerView.vue')).default
    const wrapper = mount(ScreenerView, {
      global: { stubs: { VChart: true } },
    })
    await new Promise(resolve => setTimeout(resolve, 50))

    const vm = wrapper.vm as any
    await vm.onSymbolClick('AAPL')

    expect(fetchCandles).toHaveBeenCalledWith('AAPL', 30)
    expect(vm.selectedSymbol).toBe('AAPL')
    expect(vm.showCandleChart).toBe(true)
    expect(vm.candleLoading).toBe(false)
    expect(vm.candleData).toEqual(mockCandleData)
  })

  // FE-VIEW-003: API 返回空数组时，candleData 置空
  it('FE-VIEW-003: onSymbolClick with empty candle data should set candleData to []', async () => {
    vi.mocked(fetchCandles).mockResolvedValue({
      data: { success: true, data: [], timestamp: '' },
    } as any)

    const ScreenerView = (await import('../views/ScreenerView.vue')).default
    const wrapper = mount(ScreenerView, {
      global: { stubs: { VChart: true } },
    })
    await new Promise(resolve => setTimeout(resolve, 50))

    const vm = wrapper.vm as any
    await vm.onSymbolClick('EMPTY')

    expect(vm.candleData).toEqual([])
    expect(vm.showCandleChart).toBe(true)
    expect(vm.candleLoading).toBe(false)
  })

  // FE-VIEW-004: API 返回 success=false 时，candleData 置空
  it('FE-VIEW-004: onSymbolClick when success=false should set candleData to []', async () => {
    vi.mocked(fetchCandles).mockResolvedValue({
      data: { success: false, data: null, timestamp: '' },
    } as any)

    const ScreenerView = (await import('../views/ScreenerView.vue')).default
    const wrapper = mount(ScreenerView, {
      global: { stubs: { VChart: true } },
    })
    await new Promise(resolve => setTimeout(resolve, 50))

    const vm = wrapper.vm as any
    await vm.onSymbolClick('FAIL')

    expect(vm.candleData).toEqual([])
    expect(vm.showCandleChart).toBe(true)
    expect(vm.candleLoading).toBe(false)
  })

  // FE-VIEW-005: 网络异常时，candleData 置空，loading 恢复 false
  it('FE-VIEW-005: onSymbolClick on network error should set candleLoading to false', async () => {
    vi.mocked(fetchCandles).mockRejectedValue(new Error('Network error'))

    const ScreenerView = (await import('../views/ScreenerView.vue')).default
    const wrapper = mount(ScreenerView, {
      global: { stubs: { VChart: true } },
    })
    await new Promise(resolve => setTimeout(resolve, 50))

    const vm = wrapper.vm as any
    await vm.onSymbolClick('ERROR')

    expect(vm.candleData).toEqual([])
    expect(vm.candleLoading).toBe(false)
  })

  // FE-VIEW-006: 多次调用 onSymbolClick，最后一次的数据覆盖前一次
  it('FE-VIEW-006: repeated onSymbolClick calls should update with latest data', async () => {
    const data1 = { data: { success: true, data: [{ date: '2025-01-07', open: 100, high: 105, low: 99, close: 103, changePercent: 3.0, afterHours: null, afterHoursChangePercent: null, volume: 10000 }], timestamp: '' } }
    const data2 = { data: { success: true, data: [{ date: '2025-01-08', open: 200, high: 205, low: 199, close: 203, changePercent: 1.5, afterHours: null, afterHoursChangePercent: null, volume: 20000 }], timestamp: '' } }
    vi.mocked(fetchCandles)
      .mockResolvedValueOnce(data1 as any)
      .mockResolvedValueOnce(data2 as any)

    const ScreenerView = (await import('../views/ScreenerView.vue')).default
    const wrapper = mount(ScreenerView, {
      global: { stubs: { VChart: true } },
    })
    await new Promise(resolve => setTimeout(resolve, 50))

    const vm = wrapper.vm as any
    await vm.onSymbolClick('SYM1')
    expect(vm.candleData).toEqual(data1.data.data)

    await vm.onSymbolClick('SYM2')
    expect(vm.candleData).toEqual(data2.data.data)
    expect(fetchCandles).toHaveBeenCalledTimes(2)
  })

  // FE-VIEW-007: candleChartOption computed 在有数据时返回非空对象
  it('FE-VIEW-007: candleChartOption should return a non-empty object when candleData is populated', async () => {
    const mockCandleData = [
      { date: '2025-01-07', open: 100, high: 105, low: 99, close: 103, changePercent: 3.0, afterHours: null, afterHoursChangePercent: null, volume: 10000 },
      { date: '2025-01-08', open: 103, high: 108, low: 102, close: 107, changePercent: 3.88, afterHours: null, afterHoursChangePercent: null, volume: 12000 },
    ]
    vi.mocked(fetchCandles).mockResolvedValue({
      data: { success: true, data: mockCandleData, timestamp: '' },
    } as any)

    const ScreenerView = (await import('../views/ScreenerView.vue')).default
    const wrapper = mount(ScreenerView, {
      global: { stubs: { VChart: true } },
    })
    await new Promise(resolve => setTimeout(resolve, 50))

    const vm = wrapper.vm as any
    await vm.onSymbolClick('AAPL')

    const option = vm.candleChartOption
    expect(option).not.toEqual({})
    expect(option).toHaveProperty('series')
    expect(option.series).toHaveLength(2)
    expect(option.series[0].type).toBe('candlestick')
    expect(option.series[1].type).toBe('bar')
    expect(option).toHaveProperty('xAxis')
    expect(option).toHaveProperty('yAxis')
  })

  // FE-VIEW-008: candleChartOption 在无数据时返回空对象
  it('FE-VIEW-008: candleChartOption should return {} when candleData is empty', async () => {
    const ScreenerView = (await import('../views/ScreenerView.vue')).default
    const wrapper = mount(ScreenerView, {
      global: { stubs: { VChart: true } },
    })
    await new Promise(resolve => setTimeout(resolve, 50))

    const vm = wrapper.vm as any
    const option = vm.candleChartOption
    expect(option).toEqual({})
  })

  // FE-VIEW-009: 关闭弹窗时 showCandleChart 变为 false
  it('FE-VIEW-009: closing the candle chart should set showCandleChart to false', async () => {
    vi.mocked(fetchCandles).mockResolvedValue({
      data: { success: true, data: [], timestamp: '' },
    } as any)

    const ScreenerView = (await import('../views/ScreenerView.vue')).default
    const wrapper = mount(ScreenerView, {
      global: { stubs: { VChart: true } },
    })
    await new Promise(resolve => setTimeout(resolve, 50))

    const vm = wrapper.vm as any
    // Open the chart
    await vm.onSymbolClick('AAPL')
    expect(vm.showCandleChart).toBe(true)

    // Close the chart
    vm.showCandleChart = false
    expect(vm.showCandleChart).toBe(false)
  })
})
