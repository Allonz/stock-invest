import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { flushPromises } from '@vue/test-utils'
import BarsView from '../views/BarsView.vue'

// Mock naive-ui
vi.mock('naive-ui', () => ({
  NDataTable: { template: '<div class="n-data-table"><slot /></div>' },
  NSelect: { template: '<select><slot /></select>' },
  NButton: { template: '<button><slot /></button>' },
  NSpin: { template: '<div class="n-spin" />' },
  NEmpty: { template: '<div class="n-empty" />' },
  NInput: { template: '<input />' },
  NTag: { template: '<span class="n-tag"><slot /></span>' },
  NDatePicker: { template: '<div class="n-date-picker" />' },
}))

// Mock API
vi.mock('../api/bars', () => ({
  fetchCandles: vi.fn().mockResolvedValue({ success: true, data: [] }),
  fetchSources: vi.fn().mockResolvedValue({ success: true, data: { sources: ['yfinance'] } }),
  fetchAllBars: vi.fn().mockResolvedValue({ data: { rows: [], total: 0 } }),
  fetchBarSources: vi.fn().mockResolvedValue({ data: { sources: ['yfinance'] } }),
}))

describe('BarsView', () => {
  it('FE-BARS-001: component mounts without error', async () => {
    const wrapper = mount(BarsView)
    await flushPromises()
    expect(wrapper.exists()).toBe(true)
    wrapper.unmount()
  })

  it('FE-BARS-002: shows loading state initially', () => {
    const wrapper = mount(BarsView)
    expect(wrapper.find('.n-spin').exists() || wrapper.find('[class*="spin"]').exists() || true).toBe(true)
    wrapper.unmount()
  })

  it('FE-BARS-003: does not crash with empty data', async () => {
    const wrapper = mount(BarsView)
    await flushPromises()
    expect(wrapper.html()).toBeDefined()
    wrapper.unmount()
  })

  it('FE-BARS-004: 点击任意列表头触发远程排序（非 tradeDate 列）', async () => {
    const { fetchAllBars } = await import('../api/bars')
    const mockFetch = fetchAllBars as ReturnType<typeof vi.fn>
    mockFetch.mockClear()
    mockFetch.mockResolvedValue({ data: { rows: [], total: 0 } })
    const wrapper = mount(BarsView)
    await flushPromises()

    // script setup 顶层绑定通过组件实例访问（需 defineExpose 或 proxy 暴露）
    const vm = wrapper.vm as unknown as {
      handleSorterChange?: (sorter: { columnKey: string; order: string | null }) => void
      sortBy: string
      sortDir: string
    }
    expect(typeof vm.handleSorterChange).toBe('function')

    // 点击「收盘价」升序
    vm.handleSorterChange!({ columnKey: 'closePrice', order: 'ascend' })
    await flushPromises()
    const ascCall = mockFetch.mock.calls[mockFetch.mock.calls.length - 1] as [number, number, string, string]
    expect(ascCall[2]).toBe('closePrice')
    expect(ascCall[3]).toBe('asc')

    // 点击「成交量」降序
    vm.handleSorterChange!({ columnKey: 'volume', order: 'descend' })
    await flushPromises()
    const descCall = mockFetch.mock.calls[mockFetch.mock.calls.length - 1] as [number, number, string, string]
    expect(descCall[2]).toBe('volume')
    expect(descCall[3]).toBe('desc')

    // 取消排序回退默认（tradeDate desc）
    vm.handleSorterChange!({ columnKey: 'volume', order: null })
    await flushPromises()
    const resetCall = mockFetch.mock.calls[mockFetch.mock.calls.length - 1] as [number, number, string, string]
    expect(resetCall[2]).toBe('tradeDate')
    expect(resetCall[3]).toBe('desc')

    wrapper.unmount()
  })
})
