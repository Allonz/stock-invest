<template>
  <!-- 统计卡片组件 -->
  <div class="stat-card" :style="cardStyle">
    <div class="label">{{ label }}</div>
    <div class="value" :class="valueColor">{{ value }}</div>
    <div v-if="trend" class="trend" :class="trendColor">
      {{ trendIcon }} {{ trend }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  label: string          // 卡片标签
  value: string | number // 主数值
  color?: string         // 数值颜色: blue/green/red/orange
  trend?: string         // 趋势文字
  trendUp?: boolean      // 是否为上升趋势
  width?: string         // 自定义宽度
}>(), {
  color: 'blue',
  trend: '',
  trendUp: true,
  width: ''
})

/** 数值颜色类名 */
const valueColor = computed(() => `value-${props.color}`)

/** 趋势颜色 */
const trendColor = computed(() => props.trendUp ? 'up' : 'down')

/** 趋势箭头图标 */
const trendIcon = computed(() => props.trendUp ? '↑' : '↓')

/** 自定义宽度样式 */
const cardStyle = computed(() => props.width ? { width: props.width } : {})
</script>

<style scoped>
.stat-card {
  background: var(--bg-card);
  border-radius: 10px;
  padding: 16px 20px;
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s;
}

.stat-card:hover {
  box-shadow: var(--shadow-hover);
}

.label {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-bottom: 6px;
}

.value {
  font-size: 24px;
  font-weight: 700;
}

.trend {
  font-size: 12px;
  margin-top: 4px;
}

/* 数值颜色 */
.value-blue { color: #1890ff; }
.value-green { color: #52c41a; }
.value-red { color: #f5222d; }
.value-orange { color: #fa8c16; }

/* 趋势颜色 */
.trend.up { color: #52c41a; }
.trend.down { color: #f5222d; }
</style>
