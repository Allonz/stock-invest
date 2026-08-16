<template>
  <div class="blacklist-container">
    <n-card title="股票黑名单">
      <n-data-table
        :columns="columns"
        :data="blacklistData"
        :loading="loading"
        :pagination="pagination"
        :row-key="(row: BlacklistRow) => row.id"
      />
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, h } from 'vue'
import { NCard, NDataTable, NButton, useMessage, NSpin } from 'naive-ui'
import request from '../api/request'

interface BlacklistRow {
  id: number
  symbol: string
  consecutive404Count: number
  first404Date: string
  last404Date: string
  sourceErrors: string
  status: string
  updatedAt: string
}

const message = useMessage()
const loading = ref(false)
const blacklistData = ref<BlacklistRow[]>([])

const pagination = {
  pageSize: 20,
  showSizePicker: true,
  pageSizes: [10, 20, 50, 100]
}

/** 日期字符串 → 时间戳；空值/非法值按 0 处理（排最后） */
function dateTs(s?: string): number {
  if (!s) return 0
  const t = new Date(s).getTime()
  return Number.isNaN(t) ? 0 : t
}

const columns = [
  {
    title: '代码',
    key: 'symbol',
    width: 100
  },
  {
    title: '404计数',
    key: 'consecutive404Count',
    width: 100
  },
  {
    title: '首次入黑日期',
    key: 'first404Date',
    width: 140,
    sorter: (a: BlacklistRow, b: BlacklistRow) =>
      new Date(a.first404Date).getTime() - new Date(b.first404Date).getTime()
  },
  {
    title: '最近入黑日期',
    key: 'last404Date',
    width: 140,
    defaultSortOrder: 'descend' as const,
    sorter: (a: BlacklistRow, b: BlacklistRow) =>
      dateTs(a.last404Date) - dateTs(b.last404Date)
  },
  {
    title: '错误来源',
    key: 'sourceErrors',
    width: 300,
    ellipsis: { tooltip: true }
  },
  {
    title: '状态',
    key: 'status',
    width: 80
  },
  {
    title: '更新时间',
    key: 'updatedAt',
    width: 170
  },
  {
    title: '操作',
    key: 'actions',
    width: 120,
    render: (row: BlacklistRow) => {
      return h(NButton, {
        type: 'primary',
        size: 'small',
        onClick: () => handleClear(row)
      }, { default: () => '解除黑名单' })
    }
  }
]

async function loadData() {
  loading.value = true
  try {
    const res = await request.get('/api/blacklist/list').then(res => res.data)
    // 默认按「最近入黑日期」降序：最上面展示最近的日期（空值排最后）
    blacklistData.value = (res as BlacklistRow[])
      .slice()
      .sort((a, b) => dateTs(b.last404Date) - dateTs(a.last404Date))
  } catch (e) {
    message.error('加载黑名单列表失败')
  } finally {
    loading.value = false
  }
}

async function handleClear(row: BlacklistRow) {
  try {
    await request.post(`/api/blacklist/clear?symbol=${encodeURIComponent(row.symbol)}`, {})
    message.success(`已解除 ${row.symbol} 的黑名单`)
    await loadData()
  } catch (e) {
    message.error(`解除 ${row.symbol} 黑名单失败`)
  }
}

onMounted(() => {
  loadData()
})
</script>
