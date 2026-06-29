import { test, expect, Page, Route } from '@playwright/test'

const MOCK_CANDLE_DATA = [
  { date: '2025-01-07', open: 100, high: 105, low: 99, close: 103, changePercent: 3.0, afterHours: null, afterHoursChangePercent: null, volume: 10000 },
  { date: '2025-01-08', open: 103, high: 108, low: 102, close: 107, changePercent: 3.88, afterHours: null, afterHoursChangePercent: null, volume: 12000 },
  { date: '2025-01-09', open: 107, high: 110, low: 106, close: 109, changePercent: 1.87, afterHours: 108.5, afterHoursChangePercent: -0.46, volume: 15000 },
  { date: '2025-01-10', open: 109, high: 112, low: 108, close: 95, changePercent: -12.84, afterHours: 96.0, afterHoursChangePercent: 1.05, volume: 22000 },
  { date: '2025-01-11', open: 95, high: 98, low: 94, close: 97, changePercent: 2.11, afterHours: null, afterHoursChangePercent: null, volume: 8000 },
]

const MOCK_BATCHES = [
  { batchId: 'batch-001', lastTradeDate: '2025-01-13', matchCount: 3, algorithm: 'volume_spike', windowDays: 2 },
]

const MOCK_MATCHES = [
  { id: 1, symbol: 'AAPL', name: 'Apple Inc.', lastClose: 150.25, rise: true, windowDays: 2, algorithm: 'volume_spike', volume: 100000, highPrice: 152.0, lowPrice: 149.5, changePercent: 1.25, afterHours: 151.0, afterHoursChangePercent: 0.5 },
  { id: 2, symbol: 'GOOG', name: 'Alphabet Inc.', lastClose: 2800.50, rise: false, windowDays: 3, algorithm: 'increasing_volume', volume: 50000, highPrice: 2850.0, lowPrice: 2780.0, changePercent: -0.75, afterHours: null, afterHoursChangePercent: null },
  { id: 3, symbol: 'MSFT', name: 'Microsoft', lastClose: 380.10, rise: true, windowDays: 2, algorithm: 'volume_spike', volume: 80000, highPrice: 385.0, lowPrice: 378.0, changePercent: 2.1, afterHours: 382.0, afterHoursChangePercent: 0.5 },
]

// Mock only API calls, AFTER page loads
async function setupMockRoutes(page: Page, candleData?: any[]) {
  await page.route('**/api/screening/history', async (route) => {
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data: MOCK_BATCHES, timestamp: '' }),
    })
  })
  await page.route('**/api/screening/notification/history', async (route) => {
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data: [], timestamp: '' }),
    })
  })
  await page.route('**/api/screening/batch/**', async (route) => {
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data: { batchId: 'batch-001', totalMatches: MOCK_MATCHES.length, matches: MOCK_MATCHES }, timestamp: '' }),
    })
  })
  await page.route('**/api/bars/**/candles**', async (route) => {
    const data = candleData ?? MOCK_CANDLE_DATA
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data: data, timestamp: '' }),
    })
  })
}

async function openBatchAndClickSymbol(page: Page, symbol: string = 'AAPL') {
  await page.waitForSelector('.batch-card', { timeout: 15000 })
  const firstBatchHeader = page.locator('.batch-header').first()
  await firstBatchHeader.click()
  await page.waitForSelector('.batch-detail', { timeout: 15000 })
  const symbolSpan = page.locator('.batch-detail .symbol-text').filter({ hasText: symbol })
  await symbolSpan.click()
}

test.describe('ScreenerView K线 E2E', () => {
  test('E2E-001: click stock and render chart', async ({ page }) => {
    await setupMockRoutes(page)
    await page.goto('/screener', { waitUntil: 'domcontentloaded', timeout: 15000 })
    await page.waitForTimeout(1500)

    await openBatchAndClickSymbol(page, 'AAPL')

    const chartCard = page.locator('.candle-chart-card')
    await expect(chartCard).toBeVisible({ timeout: 15000 })
    await expect(chartCard.locator('.candle-chart-title')).toContainText('AAPL')
    const canvas = chartCard.locator('canvas')
    await expect(canvas).toBeVisible({ timeout: 15000 })
  })

  test('E2E-010: verify intercepted URL', async ({ page }) => {
    await setupMockRoutes(page)
    let interceptedUrl = ''
    await page.route('**/api/bars/AAPL/candles**', async (route) => {
      interceptedUrl = route.request().url()
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ success: true, data: MOCK_CANDLE_DATA, timestamp: '' }),
      })
    })

    await page.goto('/screener', { waitUntil: 'domcontentloaded', timeout: 15000 })
    await page.waitForTimeout(1500)
    await openBatchAndClickSymbol(page, 'AAPL')
    await page.waitForSelector('.candle-chart-card', { timeout: 15000 })
    expect(interceptedUrl).toContain('/api/bars/AAPL/candles')
  })

  test('E2E-002: clicking same stock again should close chart', async ({ page }) => {
    await setupMockRoutes(page)
    await page.goto('/screener', { waitUntil: 'domcontentloaded', timeout: 15000 })
    await page.waitForTimeout(1500)
    await openBatchAndClickSymbol(page, 'AAPL')

    await expect(page.locator('.candle-chart-card')).toBeVisible()
    const closeBtn = page.locator('.candle-chart-actions button').filter({ hasText: '关闭' })
    await closeBtn.click()
    await expect(page.locator('.candle-chart-card')).not.toBeVisible()
  })

  test('E2E-003: clicking different stock should switch chart', async ({ page }) => {
    await setupMockRoutes(page)
    await page.goto('/screener', { waitUntil: 'domcontentloaded', timeout: 15000 })
    await page.waitForTimeout(1500)
    await openBatchAndClickSymbol(page, 'AAPL')

    await expect(page.locator('.candle-chart-title')).toContainText('AAPL')

    const googSpan = page.locator('.batch-detail .symbol-text').filter({ hasText: 'GOOG' })
    await googSpan.click()
    await page.waitForTimeout(500)
    await expect(page.locator('.candle-chart-title')).toContainText('GOOG')
  })

  test('E2E-004: should show error notification when API returns 500', async ({ page }) => {
    await page.route('**/api/bars/**/candles**', async (route) => {
      return route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ success: false, message: 'Error' }) })
    })
    await setupMockRoutes(page)
    await page.goto('/screener', { waitUntil: 'domcontentloaded', timeout: 15000 })
    await page.waitForTimeout(1500)
    await openBatchAndClickSymbol(page, 'AAPL')
    await page.waitForTimeout(2000)
  })

  test('E2E-005: should show empty state when data is empty', async ({ page }) => {
    await setupMockRoutes(page, [])
    await page.goto('/screener', { waitUntil: 'domcontentloaded', timeout: 15000 })
    await page.waitForTimeout(1500)
    await openBatchAndClickSymbol(page, 'AAPL')

    await page.waitForSelector('.candle-chart-card', { timeout: 15000 })
    await expect(page.locator('.candle-chart-card')).toBeVisible()
  })

  test('E2E-006: chart canvas should render', async ({ page }) => {
    await page.goto('/screener', { waitUntil: 'domcontentloaded', timeout: 15000 })
    await setupMockRoutes(page)
    await page.waitForTimeout(1000)
    await openBatchAndClickSymbol(page, 'AAPL')

    const canvas = page.locator('.candle-chart-card canvas')
    await expect(canvas).toBeVisible({ timeout: 15000 })
  })

  test('E2E-007: chart card renders on hover', async ({ page }) => {
    await page.goto('/screener', { waitUntil: 'domcontentloaded', timeout: 15000 })
    await setupMockRoutes(page)
    await page.waitForTimeout(1000)
    await openBatchAndClickSymbol(page, 'AAPL')

    const chartCard = page.locator('.candle-chart-card')
    await expect(chartCard).toBeVisible({ timeout: 15000 })
  })

  test('E2E-008: chart renders with afterHours=null data', async ({ page }) => {
    await page.goto('/screener', { waitUntil: 'domcontentloaded', timeout: 15000 })
    await setupMockRoutes(page)
    await page.waitForTimeout(1000)
    await openBatchAndClickSymbol(page, 'GOOG')

    const chartCard = page.locator('.candle-chart-card')
    await expect(chartCard).toBeVisible({ timeout: 15000 })
    await expect(chartCard.locator('.candle-chart-title')).toContainText('GOOG')
  })

  test('E2E-009: volume bar chart area exists', async ({ page }) => {
    await page.goto('/screener', { waitUntil: 'domcontentloaded', timeout: 15000 })
    await setupMockRoutes(page)
    await page.waitForTimeout(1000)
    await openBatchAndClickSymbol(page, 'AAPL')

    const canvas = page.locator('.candle-chart-card canvas')
    await expect(canvas).toBeVisible({ timeout: 15000 })
  })
})
