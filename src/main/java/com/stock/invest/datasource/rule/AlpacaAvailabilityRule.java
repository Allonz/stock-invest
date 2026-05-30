package com.stock.invest.datasource.rule;

import com.stock.invest.client.AlpacaRestClient;
import com.stock.invest.datasource.AvailabilityRule;
import com.stock.invest.datasource.DataSourceCapability;
import com.stock.invest.datasource.SourceRequirement;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Alpaca Markets 可用性检查规则。
 * 检查条件：API Key ID + Secret Key 是否已配置。
 * 纳入已有 DataSourceAvailabilityChecker 体系，
 * 前端可通过 REST API 查询该源状态。
 *
 * Alpaca 仅用于查询日历，不用于股票行情查询。
 */
@Component
@Order(500)
public class AlpacaAvailabilityRule implements AvailabilityRule {

    private final AlpacaRestClient alpacaRestClient;

    public AlpacaAvailabilityRule(AlpacaRestClient alpacaRestClient) {
        this.alpacaRestClient = alpacaRestClient;
    }

    @Override
    public String getSourceName() {
        return "alpaca";
    }

    @Override
    public SourceRequirement getRequirement() {
        return SourceRequirement.REQUIRED;
    }

    @Override
    public boolean check() {
        return alpacaRestClient.hasCredentials();
    }

    @Override
    public String getDetail() {
        if (!alpacaRestClient.hasCredentials()) {
            return "缺少 alpaca.api.key-id 或 alpaca.api.secret-key 配置";
        }
        return "Alpaca 凭证已配置（" + alpacaRestClient.getKeyIdMasked() + "，仅用于日历查询）";
    }

    @Override
    public Set<DataSourceCapability> capabilities() {
        return Set.of(DataSourceCapability.TRADING_CALENDAR);
    }
}
