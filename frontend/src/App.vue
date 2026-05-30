<template>
  <!-- 根组件: 全局配置 Naive UI 的主题和通知容器 -->
  <NConfigProvider :theme-overrides="themeOverrides" :locale="zhCN" :date-locale="dateZhCN">
    <NMessageProvider>
      <NNotificationProvider>
        <Layout />
      </NNotificationProvider>
    </NMessageProvider>
  </NConfigProvider>
</template>

<script setup lang="ts">
import { NConfigProvider, NMessageProvider, NNotificationProvider } from 'naive-ui'
import { zhCN, dateZhCN } from 'naive-ui'
import Layout from './components/Layout.vue'

// 覆盖默认主题 - 让组件风格更适配 mockup
const themeOverrides = {
  common: {
    primaryColor: '#1890ff'
  }
}
</script>
