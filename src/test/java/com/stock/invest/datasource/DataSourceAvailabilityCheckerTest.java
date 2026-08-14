package com.stock.invest.datasource;

import com.stock.invest.datasource.rule.TigerOpenAvailabilityRule;
import com.stock.invest.datasource.rule.TiingoAvailabilityRule;
import com.stock.invest.datasource.rule.TwelveDataAvailabilityRule;
import com.stock.invest.datasource.rule.YFinanceAvailabilityRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * DataSourceAvailabilityChecker 单元测试。
 * 2026-08-14：Tiger Java 数据源已删除，TigerAvailabilityRule 移除。
 */
@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class DataSourceAvailabilityCheckerTest {

    @Mock private TigerOpenAvailabilityRule tigerOpenRule;
    @Mock private TiingoAvailabilityRule tiingoRule;
    @Mock private TwelveDataAvailabilityRule twelvedataRule;
    @Mock private YFinanceAvailabilityRule yfinanceRule;

    private DataSourceAvailabilityChecker checker;

    private void mockRule(AvailabilityRule rule, String name, SourceRequirement req, boolean available, String detail) {
        when(rule.getSourceName()).thenReturn(name);
        when(rule.getRequirement()).thenReturn(req);
        when(rule.check()).thenReturn(available);
        when(rule.getDetail()).thenReturn(detail);
    }

    private DataSourceAvailabilityChecker createChecker(List<AvailabilityRule> rules) {
        return new DataSourceAvailabilityChecker(rules);
    }

    @Test
    @DisplayName("TC-AVAIL-003: YFinance 始终可用")
    void yfinance_alwaysAvailable() {
        mockRule(yfinanceRule, "yfinance", SourceRequirement.OPTIONAL, true, "无需 API Key，始终可用");
        checker = createChecker(Arrays.asList(yfinanceRule));
        checker.init();

        assertTrue(checker.isAvailable("yfinance"));
        Optional<SourceStatus> status = checker.getStatus("yfinance");
        assertTrue(status.isPresent());
        assertTrue(status.get().isAvailable());
        assertNull(status.get().getReason());
    }

    @Test
    @DisplayName("TC-AVAIL-004: TwelveData 始终可用")
    void twelvedata_alwaysAvailable() {
        mockRule(twelvedataRule, "twelvedata", SourceRequirement.OPTIONAL, true, "未配置 API Key，降级使用（速率受限）");
        checker = createChecker(Arrays.asList(twelvedataRule));
        checker.init();

        assertTrue(checker.isAvailable("twelvedata"));
        Optional<SourceStatus> status = checker.getStatus("twelvedata");
        assertTrue(status.isPresent());
        assertTrue(status.get().isAvailable());
        assertNull(status.get().getReason());
    }

    @Test
    @DisplayName("TC-AVAIL-005: Tiingo API Key 已配置时标记为可用")
    void tiingoKeyConfigured_shouldBeAvailable() {
        mockRule(tiingoRule, "tiingo", SourceRequirement.REQUIRED, true, "已配置 Tiingo API Token");
        checker = createChecker(Arrays.asList(tiingoRule));
        checker.init();

        assertTrue(checker.isAvailable("tiingo"));
        Optional<SourceStatus> status = checker.getStatus("tiingo");
        assertTrue(status.isPresent());
        assertTrue(status.get().isAvailable());
        assertNull(status.get().getReason());
    }

    @Test
    @DisplayName("TC-AVAIL-006: Tiingo API Key 未配置时标记为不可用")
    void tiingoKeyMissing_shouldBeUnavailable() {
        mockRule(tiingoRule, "tiingo", SourceRequirement.REQUIRED, false, "缺失 Tiingo API Token");
        checker = createChecker(Arrays.asList(tiingoRule));
        checker.init();

        assertFalse(checker.isAvailable("tiingo"));
        Optional<SourceStatus> status = checker.getStatus("tiingo");
        assertTrue(status.isPresent());
        assertFalse(status.get().isAvailable());
        assertNotNull(status.get().getReason());
        assertTrue(status.get().getReason().contains("缺失") || status.get().getReason().contains("Token"));
    }

    @Test
    @DisplayName("TC-AVAIL-007: 混合场景 - getAvailableDataSources 返回正确列表")
    void mixedScenarios_getAvailableDataSources() {
        mockRule(tigerOpenRule, "tigeropen", SourceRequirement.REQUIRED, true, "已配置 tiger_id、private_key、account");
        mockRule(yfinanceRule, "yfinance", SourceRequirement.OPTIONAL, true, "无需 API Key，始终可用");
        mockRule(twelvedataRule, "twelvedata", SourceRequirement.OPTIONAL, true, "未配置 API Key，降级使用（速率受限）");
        mockRule(tiingoRule, "tiingo", SourceRequirement.REQUIRED, false, "缺失 Tiingo API Token");

        checker = createChecker(Arrays.asList(tigerOpenRule, yfinanceRule, twelvedataRule, tiingoRule));
        checker.init();

        List<String> available = checker.getAvailableSourceNames();
        assertEquals(3, available.size());
        assertTrue(available.contains("tigeropen"));
        assertTrue(available.contains("yfinance"));
        assertTrue(available.contains("twelvedata"));
        assertFalse(available.contains("tiingo"));

        Map<String, SourceStatus> all = checker.getAllStatus();
        assertTrue(all.get("tigeropen").isAvailable());
        assertTrue(all.get("yfinance").isAvailable());
        assertTrue(all.get("twelvedata").isAvailable());
        assertFalse(all.get("tiingo").isAvailable());
        assertNotNull(all.get("tiingo").getReason());
    }
}
