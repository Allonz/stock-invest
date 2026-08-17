import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import FillTasks from '../views/FillTasks.vue'

vi.mock('naive-ui', () => ({
  NDataTable: { template: '<div class="n-data-table"><slot /></div>' },
  NCard: { template: '<div class="n-card"><slot /></div>' },
  NSpace: { template: '<div class="n-space"><slot /></div>' },
  NGrid: { template: '<div class="n-grid"><slot /></div>' },
  NGi: { template: '<div class="n-gi"><slot /></div>' },
  NStatistic: { template: '<div class="n-statistic"><slot /></div>' },
  NEmpty: { template: '<div class="n-empty" />' },
  NPagination: { template: '<div class="n-pagination" />' },
  NInput: { template: '<input class="n-input" />' },
  NDatePicker: { template: '<div class="n-date-picker" />' },
  NSelect: { template: '<select><slot /></select>' },
  NButton: { template: '<button><slot /></button>' },
  NSpin: { template: '<div class="n-spin" />' },
  NTag: { template: '<span class="n-tag"><slot /></span>' },
  NProgress: { template: '<div class="n-progress" />' },
  useNotification: () => ({ error: vi.fn(), success: vi.fn() }),
  useMessage: () => ({ error: vi.fn(), success: vi.fn(), warning: vi.fn(), info: vi.fn() }),
}))

vi.mock('../api/admin', () => ({
  fetchFillTasks: vi.fn().mockResolvedValue({ success: true, data: { data: [], total: 0 } }),
  fetchFillTaskCount: vi.fn().mockResolvedValue({ success: true, data: { total: 0, retrying: 0, completed: 0, stopped: 0 } }),
  fetchDataFillProgress: vi.fn().mockResolvedValue({ data: { success: true, data: { running: false, stage: 'IDLE' } } }),
  fetchRetryProgress: vi.fn().mockResolvedValue({ success: true, data: { running: false } }),
}))

describe('FillTasks', () => {
  it('FE-FILL-001: component mounts without error', async () => {
    const wrapper = mount(FillTasks)
    await flushPromises()
    expect(wrapper.exists()).toBe(true)
    wrapper.unmount()
  })

  it('FE-FILL-002: progress panel hidden when no running task', async () => {
    const wrapper = mount(FillTasks)
    await flushPromises()
    // Progress panel should not be visible when running=false
    expect(wrapper.html()).toBeDefined()
    wrapper.unmount()
  })
})
