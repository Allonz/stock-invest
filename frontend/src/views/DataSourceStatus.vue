<template>
  <!-- 数据源状态页面 -->
  <div class="datasource-status">
    <div v-if="loading" class="loading-container">
      <NSpin size="large" />
      <p style="margin-top: 16px; color: #999">加载数据源状态...</p>
    </div>

    <template v-else>
      <!-- 统计概览 -->
      <div class="stat-cards">
        <StatCard
          label="数据源总数"
          :value="sources.length"
          color="blue"
          trend="已接入数据源"
        />
        <StatCard
          label="可用"
          :value="availableCount"
          color="green"
          :trend-up="true"
          trend="正常运行中"
        />
        <StatCard
          label="离线"
          :value="offlineCount"
          color="red"
          :trend-up="false"
          trend="需要关注"
        />
        <StatCard
          label="API Key 就绪"
          :value="apiKeyReadyCount"
          color="orange"
          trend="已配置密钥"
        />
      </div>

      <!-- 数据源列表 -->
      <NCard title="数据源详情" size="small" :bordered="false" class="status-card">
        <template #header-extra>
          <div style="display: flex; gap: 8px;">
            <NButton size="tiny" @click="loadHealth" :loading="healthLoading">🏥 健康检查</NButton>
            <NButton size="tiny" @click="loadStatus">🔄 刷新</NButton>
          </div>
        </template>

        <NDataTable
          :columns="columns"
          :data="sources"
          :bordered="false"
          :single-line="false"
          size="small"
        />

        <!-- 健康检查结果 -->
        <div v-if="healthData.length > 0" style="margin-top: 16px; padding: 12px; background: #f6ffed; border-radius: 8px;">
          <div style="font-weight: 600; margin-bottom: 8px;">
            {{ allHealthy ? '✅ 所有数据源健康' : '⚠️ 部分数据源异常' }}
            <span style="font-weight: normal; color: #999; margin-left: 8px;">{{ healthData.filter(h => h.healthy).length }}/{{ healthData.length }} 健康</span>
          </div>
          <div v-for="h in healthData" :key="h.name" style="display: flex; align-items: center; gap: 8px; padding: 4px 0; font-size: 13px;">
            <span :style="{ color: h.healthy ? '#52c41a' : '#f5222d' }">{{ h.healthy ? '✅' : '❌' }}</span>
            <span style="font-weight: 500;">{{ h.name }}</span>
            <span v-if="!h.healthy" style="color: #999;">({{ h.reason || '未配置 API Key' }})</span>
          </div>
        </div>
      </NCard>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, h } from 'vue'
import { NSpin, NButton, NDataTable, NCard, NTag, NIcon, useNotification } from 'naive-ui'
import { CheckmarkCircle, CloseCircle, AlertCircle } from '@vicons/ionicons5'
import StatCard from '../components/StatCard.vue'
import { fetchDataSourceStatus, fetchDataSourceHealth } from '../api/datasource'
import type { DataSource, DataSourceHealth } from '../api/datasource'

const notification = useNotification()

// ============ 状态 ============
const loading = ref(true)
const sources = ref<DataSource[]>([])
const healthData = ref<DataSourceHealth[]>([])
const allHealthy = ref(false)
const healthLoading = ref(false)

// ============ 统计 ============
const availableCount = computed(() => sources.value.filter(s => s.available).length)
const offlineCount = computed(() => sources.value.filter(s => !s.available).length)
const apiKeyReadyCount = computed(() => sources.value.filter(s => s.hasApiKey).length)

// ============ 表格列 ============
const columns = [
  {
    title: '数据源名称',
    key: 'name',
    width: 150,
    render: (row: DataSource) => h('span', { style: 'font-weight: 600;' }, row.name)
  },
  {
    title: '运行状态',
    key: 'available',
    width: 120,
    render: (row: DataSource) => h(NTag, {
      type: row.available ? 'success' : 'error',
      size: 'small',
      bordered: false
    }, {
      default: () => row.available ? '✅ 在线' : '❌ 离线'
    })
  },
  {
    title: 'API Key',
    key: 'hasApiKey',
    width: 120,
    render: (row: DataSource) => h(NTag, {
      type: row.hasApiKey ? 'success' : 'warning',
      size: 'small',
      bordered: false
    }, {
      default: () => row.hasApiKey ? '🔑 已配置' : '⚠️ 未配置'
    })
  },
  {
    title: '详情',
    key: 'reason',
    render: (row: DataSource) => row.reason || '运行正常'
  }
]

// ============ 数据加载 ============
async function loadStatus() {
  loading.value = true
  try {
    const res = await fetchDataSourceStatus()
    if (res.data.success) {
      sources.value = res.data.data.sources
    } else {
      notification.error({ title: '加载失败', content: '数据源状态接口异常', duration: 3000 })
    }
  } catch (err: any) {
    notification.error({ title: '请求失败', content: err.message || '网络异常', duration: 3000 })
  } finally {
    loading.value = false
  }
}

onMounted(loadStatus)

/** 加载健康状态 */
async function loadHealth() {
  healthLoading.value = true
  try {
    const res = await fetchDataSourceHealth()
    if (res.data.success) {
      healthData.value = res.data.data.sources
      allHealthy.value = res.data.data.allHealthy
    }
  } catch (err: any) {
    notification.error({ title: '健康检查失败', content: err.message, duration: 3000 })
  } finally {
    healthLoading.value = false
  }
}
</script>

<style scoped>
.datasource-status {
  min-height: 200px;
}

.loading-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
}

.stat-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

@media (max-width: 900px) {
  .stat-cards {
    grid-template-columns: repeat(2, 1fr);
  }
}

.status-card {
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
</style>
