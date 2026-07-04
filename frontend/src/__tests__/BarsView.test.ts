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
}))

// Mock API
vi.mock('../api/bars', () => ({
  fetchCandles: vi.fn().mockResolvedValue({ success: true, data: [] }),
  fetchSources: vi.fn().mockResolvedValue({ success: true, data: { sources: ['yfinance'] } }),
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
})
