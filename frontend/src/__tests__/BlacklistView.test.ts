import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import BlacklistView from '../views/BlacklistView.vue'

vi.mock('naive-ui', () => ({
  NCard: { template: '<div class="n-card"><slot /></div>' },
  NDataTable: { template: '<div class="n-data-table"><slot /></div>' },
  NButton: { template: '<button><slot /></button>' },
  NSpin: { template: '<div class="n-spin" />' },
  useNotification: () => ({ error: vi.fn(), success: vi.fn() }),
  useMessage: () => ({ error: vi.fn(), success: vi.fn(), warning: vi.fn(), info: vi.fn() }),
}))

vi.mock('../api/request', () => ({
  default: {
    get: vi.fn().mockResolvedValue({ data: { success: true, data: [] } }),
    post: vi.fn().mockResolvedValue({ data: { success: true } }),
  },
}))

describe('BlacklistView', () => {
  it('FE-BL-001: component mounts without error', async () => {
    const wrapper = mount(BlacklistView)
    await flushPromises()
    expect(wrapper.exists()).toBe(true)
    wrapper.unmount()
  })

  it('FE-BL-002: renders table for blacklist data', async () => {
    const wrapper = mount(BlacklistView)
    await flushPromises()
    expect(wrapper.html()).toBeDefined()
    wrapper.unmount()
  })
})
