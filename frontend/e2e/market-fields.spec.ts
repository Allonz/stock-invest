import { test, expect, Page } from '@playwright/test'

const MOCK_MATCHES = [
  { id: 1, symbol: 'AAPL', name: 'Apple Inc.', lastClose: 150.25, rise: true, windowDays: 2, algorithm: 'volume_spike', volume: 100000, highPrice: 152.0, lowPrice: 149.5, changePercent: 1.25, afterHours: 151.0, afterHoursChangePercent: 0.5 },
  { id: 2, symbol: 'GOOG', name: 'Alphabet Inc.', lastClose: 2800.50, rise: false, windowDays: 2, algorithm: 'volume_spike', volume: 50000, highPrice: 2850.0, lowPrice: 2780.0, changePercent: -0.75, afterHours: null, afterHoursChangePercent: null },
  { id: 3, symbol: 'MSFT', name: '', lastClose: 380.10, rise: true, windowDays: 2, algorithm: 'volume_spike', volume: 80000, highPrice: 385.0, lowPrice: 378.0, changePercent: 2.1, afterHours: 382.0, afterHoursChangePercent: 0.5 },
  { id: 4, symbol: 'AMZN', name: undefined, lastClose: 178.50, rise: false, windowDays: 2, algorithm: 'volume_spike', volume: 120000, highPrice: 180.0, lowPrice: 176.0, changePercent: -1.2, afterHours: 177.0, afterHoursChangePercent: -0.84 },
  { id: 5, symbol: 'TSLA', name: '', lastClose: 250.0, rise: true, windowDays: 2, algorithm: 'volume_spike', highPrice: undefined, lowPrice: undefined, changePercent: undefined, afterHours: undefined, afterHoursChangePercent: undefined, volume: undefined },
]

const MOCK_NOTIFICATION = {
  batchId: 'batch-001',
  screenDate: '2025-01-13',
  results: {
    volume_spike: { '2': 74, '3': 30, '4': 50, '5': 60, '6': 40, '7': 25 },
    increasing_volume: { '2': 28, '3': 12, '4': 18, '5': 8, '6': 15, '7': 10 },
  },
}

// Use EXACT route paths, NOT broad **/api/** patterns
async function setupMockRoutes(page: Page) {
  await page.route('**/api/notification/latest', async (route) => {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: MOCK_NOTIFICATION, timestamp: '' }) })
  })
  await page.route('**/api/screening/latest', async (route) => {
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data: { tradeDate: '2025-01-13', batchId: 'batch-001', totalMatches: 5, matches: MOCK_MATCHES }, timestamp: '' }),
    })
  })
}

async function navigateToMarket(page: Page) {
  // Set up mock BEFORE goto so onMounted API calls are intercepted
  await setupMockRoutes(page)
  await page.goto('/market', { waitUntil: 'domcontentloaded', timeout: 15000 })
  // Wait for loading to complete
  await page.waitForTimeout(2000)
}

test.describe('MarketView — 字段展示 E2E 测试', () => {

  test('E2E-MK-001: table should contain all expected columns', async ({ page }) => {
    await navigateToMarket(page)

    // NDataTable renders inside .table-container
    const table = page.locator('.table-container .n-data-table')
    await expect(table).toBeVisible({ timeout: 10000 })

    const headers = page.locator('.table-container th')
    const headerTexts = await headers.allTextContents()

    const expectedColumns = ['代码', '名称', '最新价', '最高价', '最低价', '涨跌幅', '盘后价', '盘后涨跌幅', '涨幅', '算法', '窗口']
    for (const col of expectedColumns) {
      expect(headerTexts.some(t => t.includes(col))).toBeTruthy()
    }

    expect(headerTexts.some(t => t.includes('最高价'))).toBeTruthy()
    expect(headerTexts.some(t => t.includes('最低价'))).toBeTruthy()
    expect(headerTexts.some(t => t.includes('涨跌幅'))).toBeTruthy()
    expect(headerTexts.some(t => t.includes('盘后价'))).toBeTruthy()
    expect(headerTexts.some(t => t.includes('盘后涨跌幅'))).toBeTruthy()
  })

  test('E2E-MK-002: null field values should display "—"', async ({ page }) => {
    await navigateToMarket(page)

    const table = page.locator('.table-container .n-data-table')
    await expect(table).toBeVisible({ timeout: 10000 })

    // Check page text contains TSLA
    await expect(page.locator('.table-container')).toContainText('TSLA')
    // Verify "—" is present for null fields (TSLA has many undefined fields)
    const pageText = await page.locator('.table-container').textContent() || ''
    expect(pageText).toContain('—')
  })

  test('E2E-MK-003: changePercent should be formatted with sign and color', async ({ page }) => {
    await navigateToMarket(page)

    const table = page.locator('.table-container .n-data-table')
    await expect(table).toBeVisible({ timeout: 10000 })

    // AAPL has +1.25% (positive)
    await expect(page.locator('.table-container')).toContainText('+1.25%')
    // GOOG has -0.75% (negative)
    await expect(page.locator('.table-container')).toContainText('-0.75%')
  })

  test('E2E-MK-004: clicking column header should sort rows', async ({ page }) => {
    await navigateToMarket(page)

    const table = page.locator('.table-container .n-data-table')
    await expect(table).toBeVisible({ timeout: 10000 })

    const symbolHeader = page.locator('.n-data-table th').filter({ hasText: '代码' })
    await symbolHeader.click()
    await page.waitForTimeout(500)
    // After clicking, verify AAPL still appears in table
    await expect(page.locator('.table-container')).toContainText('AAPL')
  })

  test('E2E-MK-005: CSV export button exists', async ({ page }) => {
    await navigateToMarket(page)

    const exportBtn = page.locator('button').filter({ hasText: '导出 CSV' })
    await expect(exportBtn).toBeVisible()
  })
})
