import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

// ========== Mock all external dependencies FIRST ==========

// Mock naive-ui
vi.mock('naive-ui', () => ({
  NSpin: { name: 'NSpin', template: '<div class="mock-nspin"><slot /></div>' },
  NButton: { name: 'NButton', template: '<button class="mock-nbutton" @click="$emit(\'click\')"><slot /></button>' },
  NDataTable: { name: 'NDataTable', template: '<div class="mock-ndatatable"><slot /></div>' },
  NTag: {
    name: 'NTag',
    props: ['type', 'size', 'bordered', 'color'],
    template: '<span class="mock-ntag" :data-type="type"><slot /></span>',
  },
  useNotification: () => ({
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
  }),
}))

// Mock StatCard
vi.mock('../components/StatCard.vue', () => ({
  default: {
    name: 'StatCard',
    props: ['label', 'value', 'color', 'trend', 'trendUp'],
    template: '<div class="mock-statcard">{{ label }}: {{ value }}</div>',
  },
}))

// Mock screening API
vi.mock('../api/screening', () => ({
  fetchLatestNotification: vi.fn(),
  fetchLatestScreening: vi.fn(),
}))

import { fetchLatestNotification, fetchLatestScreening } from '../api/screening'
import type { ScreeningMatch } from '../api/screening'

describe('MarketView.vue — 字段列展示', () => {
  beforeEach(() => {
    vi.clearAllMocks()

    // Default mock data
    const mockScreeningResponse = {
      data: {
        success: true,
        data: {
          tradeDate: '2025-01-13',
          batchId: 'batch-001',
          totalMatches: 5,
          matches: [
            {
              id: 1, symbol: 'AAPL', name: 'Apple Inc.', lastClose: 150.25, rise: true,
              windowDays: 2, algorithm: 'volume_spike', volume: 100000,
              highPrice: 152.0, lowPrice: 149.5, changePercent: 1.25,
              afterHours: 151.0, afterHoursChangePercent: 0.5,
            },
            {
              id: 2, symbol: 'GOOG', name: 'Alphabet Inc.', lastClose: 2800.50, rise: false,
              windowDays: 2, algorithm: 'increasing_volume', volume: 50000,
              highPrice: 2850.0, lowPrice: 2780.0, changePercent: -0.75,
              afterHours: null, afterHoursChangePercent: null,
            },
            {
              id: 3, symbol: 'MSFT', name: '', lastClose: 380.10, rise: true,
              windowDays: 3, algorithm: 'volume_spike', volume: 80000,
              highPrice: 385.0, lowPrice: 378.0, changePercent: 2.1,
              afterHours: 382.0, afterHoursChangePercent: 0.5,
            },
            {
              id: 4, symbol: 'AMZN', name: undefined, lastClose: 178.50, rise: false,
              windowDays: 2, algorithm: 'volume_spike', volume: 120000,
              highPrice: 180.0, lowPrice: 176.0, changePercent: -1.2,
              afterHours: 177.0, afterHoursChangePercent: -0.84,
            },
            {
              id: 5, symbol: 'TSLA', name: undefined, lastClose: 250.0, rise: true,
              windowDays: 4, algorithm: 'increasing_volume',
            },
          ],
        },
        timestamp: '2025-01-13T10:00:00Z',
      },
    }

    const mockNotificationResponse = {
      data: {
        success: true,
        data: {
          batchId: 'batch-001',
          screenDate: '2025-01-13',
          results: {
            volume_spike: { '2': 74, '3': 30, '4': 50, '5': 60, '6': 40, '7': 25 },
            increasing_volume: { '2': 28, '3': 12, '4': 18, '5': 8, '6': 15, '7': 10 },
          },
        },
        timestamp: '2025-01-13T10:00:00Z',
      },
    }

    vi.mocked(fetchLatestScreening).mockResolvedValue(mockScreeningResponse as any)
    vi.mocked(fetchLatestNotification).mockResolvedValue(mockNotificationResponse as any)
  })

  // E2E-MK-001: 组件挂载后，统计卡片显示正确的数量和日期
  it('E2E-MK-001: should mount and display stat cards with correct values', async () => {
    const MarketView = (await import('../views/MarketView.vue')).default
    const wrapper = mount(MarketView, {
      global: { stubs: { StatCard: true, NSpin: true, NButton: true, NDataTable: true } },
    })

    // Wait for onMounted/loadData to resolve
    await new Promise(resolve => setTimeout(resolve, 100))

    const statCards = wrapper.findAllComponents({ name: 'StatCard' })
    expect(statCards.length).toBe(4)
  })

  // E2E-MK-002: compact-view 渲染并显示所有股票名称和代码
  it('E2E-MK-002: compact-view should render stock names and symbols', async () => {
    const MarketView = (await import('../views/MarketView.vue')).default
    const wrapper = mount(MarketView, {
      global: { stubs: { StatCard: true, NSpin: true, NButton: true } },
    })
    await new Promise(resolve => setTimeout(resolve, 100))

    // Check that compact-table exists
    const table = wrapper.find('.compact-table')
    expect(table.exists()).toBe(true)

    // Name row cells (first row)
    const nameRow = table.findAll('tbody tr').at(0)!
    const nameCells = nameRow.findAll('td.stock-col')
    expect(nameCells.length).toBe(2)
    expect(nameCells[0].text()).toBe('Apple Inc.')
    expect(nameCells[1].text()).toBe('—') // AMZN name is undefined

    // Symbol row cells (second row) - symbol text is inside a div
    const symbolRow = table.findAll('tbody tr').at(1)!
    const symbolCells = symbolRow.findAll('td.stock-col')
    expect(symbolCells[0].text()).toContain('AAPL')
    expect(symbolCells[1].text()).toContain('AMZN')
  })

  // E2E-MK-003: 点击股票列触发 onSymbolClick
  it('E2E-MK-003: clicking a stock column should trigger onSymbolClick', async () => {
    const MarketView = (await import('../views/MarketView.vue')).default
    const wrapper = mount(MarketView, {
      global: { stubs: { StatCard: true, NSpin: true, NButton: true } },
    })
    await new Promise(resolve => setTimeout(resolve, 100))

    const vm = wrapper.vm as any
    // Spy on onSymbolClick
    const spy = vi.spyOn(vm, 'onSymbolClick')

    const firstStockCell = wrapper.find('td.stock-col')
    expect(firstStockCell.exists()).toBe(true)
    await firstStockCell.trigger('click')
    expect(spy).toHaveBeenCalledWith('AAPL')
  })

  // E2E-MK-004: 无名称时显示 — 
  it('E2E-MK-004: missing name should show —', async () => {
    const MarketView = (await import('../views/MarketView.vue')).default
    const wrapper = mount(MarketView, {
      global: { stubs: { StatCard: true, NSpin: true, NButton: true } },
    })
    await new Promise(resolve => setTimeout(resolve, 100))

    const nameCells = wrapper.findAll('tbody tr').at(0)!.findAll('td.stock-col')
    // AAPL has name 'Apple Inc.', AMZN has undefined name → '—'
    expect(nameCells[1].text()).toBe('—')
  })

  // E2E-MK-005: 行标签显示 名称 和 代码
  it('E2E-MK-005: row labels should show 名称 and 代码', async () => {
    const MarketView = (await import('../views/MarketView.vue')).default
    const wrapper = mount(MarketView, {
      global: { stubs: { StatCard: true, NSpin: true, NButton: true } },
    })
    await new Promise(resolve => setTimeout(resolve, 100))

    const rowLabels = wrapper.findAll('.row-label-cell')
    expect(rowLabels.length).toBe(3) // 名称 + 代码 + (复制行空标签)
    expect(rowLabels[0].text()).toBe('名称')
    expect(rowLabels[1].text()).toBe('代码')
    expect(rowLabels[2].text()).toBe('')
  })

  // E2E-MK-006: compact-footer 显示计数
  it('E2E-MK-006: compact-footer should show stock count', async () => {
    const MarketView = (await import('../views/MarketView.vue')).default
    const wrapper = mount(MarketView, {
      global: { stubs: { StatCard: true, NSpin: true, NButton: true } },
    })
    await new Promise(resolve => setTimeout(resolve, 100))

    const footer = wrapper.find('.compact-footer')
    expect(footer.exists()).toBe(true)
    expect(footer.text()).toContain('2')
  })

  // E2E-MK-007: 筛选后的数据 filteredMatches 按窗口和算法过滤
  it('E2E-MK-007: filteredMatches should filter by selectedWindow and selectedAlgo', async () => {
    const MarketView = (await import('../views/MarketView.vue')).default
    const wrapper = mount(MarketView, {
      global: { stubs: { StatCard: true, NSpin: true, NButton: true } },
    })
    await new Promise(resolve => setTimeout(resolve, 100))

    const vm = wrapper.vm as any

    // Default filter: window=2, algo='volume_spike'
    // Only AAPL (id=1) and AMZN (id=4) match
    expect(vm.filteredMatches.length).toBe(2)
    expect(vm.filteredMatches.map((m: any) => m.symbol)).toEqual(['AAPL', 'AMZN'])

    // algo='all', window=2 → AAPL, GOOG, AMZN
    vm.selectedAlgo = 'all'
    expect(vm.filteredMatches.length).toBe(3)
    expect(vm.filteredMatches.map((m: any) => m.symbol)).toEqual(['AAPL', 'GOOG', 'AMZN'])

    // window=3, algo='all' → MSFT only
    vm.selectedWindow = 3
    expect(vm.filteredMatches.length).toBe(1)
    expect(vm.filteredMatches[0].symbol).toBe('MSFT')

    // window=4, algo='increasing_volume' → TSLA only
    vm.selectedWindow = 4
    vm.selectedAlgo = 'increasing_volume'
    expect(vm.filteredMatches.length).toBe(1)
    expect(vm.filteredMatches[0].symbol).toBe('TSLA')

    // window=7 → no matches
    vm.selectedWindow = 7
    vm.selectedAlgo = 'all'
    expect(vm.filteredMatches.length).toBe(0)
  })
})
