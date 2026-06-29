// K线图表共享逻辑 composable
// 抽取自 MarketView.vue 与 ScreenerView.vue 中完全重复的 K线相关状态与函数
import { ref, computed } from 'vue'
import { useNotification } from 'naive-ui'

import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { CandlestickChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, DataZoomComponent, TitleComponent } from 'echarts/components'

import { fetchCandles } from '../api/bars'
import type { CandleData } from '../api/bars'

// ECharts 按需注册（在模块加载时执行一次）
use([CanvasRenderer, CandlestickChart, BarChart, GridComponent, TooltipComponent, DataZoomComponent, TitleComponent])

/**
 * K线图表共享逻辑。
 *
 * 提供：
 * - 状态: showCandleChart, candleLoading, candleData, selectedSymbol, copiedSymbol
 * - 方法: onSymbolClick(symbol), copySymbol(symbol)
 * - 计算属性: candleChartOption
 */
export function useCandleChart() {
  const notification = useNotification()

  // ===================== K线图表状态 =====================
  const selectedSymbol = ref<string | null>(null)
  const candleLoading = ref(false)
  const candleData = ref<CandleData[]>([])
  const showCandleChart = ref(false)

  /** 复制反馈 */
  const copiedSymbol = ref<string | null>(null)

  /** 点击股票代码查看 K 线图 */
  async function onSymbolClick(symbol: string) {
    selectedSymbol.value = symbol
    showCandleChart.value = true
    candleLoading.value = true
    try {
      const res = await fetchCandles(symbol, 30)
      if (res.data.success) {
        candleData.value = res.data.data
      } else {
        notification.warning({ title: 'K线数据加载失败', duration: 3000 })
        candleData.value = []
      }
    } catch (err: any) {
      notification.error({ title: 'K线数据异常', content: err.message, duration: 3000 })
      candleData.value = []
    } finally {
      candleLoading.value = false
    }
  }

  /** 复制股票代码到剪贴板 */
  function copySymbol(sym: string) {
    navigator.clipboard.writeText(sym)
    copiedSymbol.value = sym
    setTimeout(() => { copiedSymbol.value = null }, 1000)
  }

  /** K线图表配置 */
  const candleChartOption = computed(() => {
    const data = candleData.value
    if (!data || data.length === 0) return {}

    const dates = data.map(d => d.date)
    const ohlc = data.map(d => [d.open, d.close, d.low, d.high])
    const volumes = data.map(d => d.volume)

    return {
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params: any[]) => {
          try {
            if (!params || params.length === 0) return ''
            const idx = params[0].dataIndex
            const d = data[idx]
            if (!d) return ''
            // 成交量格式化：万/亿
            const fmtVolume = (v: number) => {
              if (v >= 1e8) return (v / 1e8).toFixed(2) + '亿'
              if (v >= 1e4) return (v / 1e4).toFixed(2) + '万'
              return v.toLocaleString()
            }
            return [
              `<div style="font-weight:600;margin-bottom:4px;">${d.date}</div>`,
              `开盘: <b>${d.open.toFixed(4)}</b>`,
              `收盘: <b>${d.close.toFixed(4)}</b>`,
              `最高: <b>${d.high.toFixed(4)}</b>`,
              `最低: <b>${d.low.toFixed(4)}</b>`,
              `涨跌幅: <b>${d.changePercent != null ? d.changePercent.toFixed(2) + '%' : '—'}</b>`,
              `成交量: <b>${fmtVolume(d.volume)}</b>`,
              d.afterHours != null ? `盘后价: <b>${d.afterHours.toFixed(4)}</b>` : '',
              d.afterHoursChangePercent != null ? `盘后涨跌幅: <b>${d.afterHoursChangePercent.toFixed(2)}%</b>` : ''
            ].filter(Boolean).join('<br/>')
          } catch {
            return ''
          }
        }
      },
      grid: [
        { left: '8%', right: '8%', top: '10%', height: '60%' },
        { left: '8%', right: '8%', top: '78%', height: '12%' }
      ],
      xAxis: [
        {
          type: 'category',
          data: dates,
          axisLine: { onZero: false },
          axisTick: { alignWithLabel: true },
          splitLine: { show: false },
          axisLabel: { rotate: 30, fontSize: 10 },
          gridIndex: 0
        },
        {
          type: 'category',
          data: dates,
          axisLabel: { show: false },
          axisTick: { show: false },
          splitLine: { show: false },
          gridIndex: 1
        }
      ],
      yAxis: [
        {
          type: 'value',
          scale: true,
          gridIndex: 0
        },
        {
          type: 'value',
          scale: true,
          splitNumber: 2,
          name: 'VOL',
          nameLocation: 'start',
          nameGap: 2,
          nameTextStyle: { fontSize: 10, color: '#999' },
          axisLabel: {
            show: true,
            fontSize: 9,
            color: '#999',
            formatter: (v: number) => {
              if (v >= 1e8) return (v / 1e8).toFixed(1) + '亿'
              if (v >= 1e4) return (v / 1e4).toFixed(0) + '万'
              return v.toLocaleString()
            }
          },
          splitLine: { show: false },
          gridIndex: 1
        }
      ],
      dataZoom: [
        {
          type: 'inside',
          xAxisIndex: [0, 1],
          zoomOnMouseWheel: true,
          moveOnMouseMove: true
        },
        {
          type: 'slider',
          xAxisIndex: [0, 1],
          bottom: 8, height: 16,
          realtime: true,
          brushSelect: false,
          showDataShadow: false
        }
      ],
      animation: false,
      series: [
        {
          type: 'candlestick',
          data: ohlc,
          xAxisIndex: 0,
          yAxisIndex: 0,
          barWidth: '25%',
          barMaxWidth: 30,
          itemStyle: {
            color: '#ef232a',
            color0: '#14b143',
            borderColor: '#ef232a',
            borderColor0: '#14b143'
          }
        },
        {
          type: 'bar',
          data: volumes,
          xAxisIndex: 1,
          yAxisIndex: 1,
          barWidth: '35%',
          barMaxWidth: 20,
          itemStyle: {
            color: (params: any) => {
              const d = data[params.dataIndex]
              return d ? (d.close >= d.open ? '#14b143' : '#ef232a') : '#999'
            }
          }
        }
      ]
    }
  })

  return {
    showCandleChart,
    candleLoading,
    candleData,
    selectedSymbol,
    copiedSymbol,
    onSymbolClick,
    copySymbol,
    candleChartOption
  }
}
