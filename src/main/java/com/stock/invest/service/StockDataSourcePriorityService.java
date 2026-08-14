package com.stock.invest.service;

import com.stock.invest.entity.StockDataSourcePriority;
import com.stock.invest.repository.StockDataSourcePriorityRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StockDataSourcePriorityService {

    private static final Logger log = LoggerFactory.getLogger(StockDataSourcePriorityService.class);

    public static final List<String> DEFAULT_DATA_SOURCE_ORDER = List.of(
            "yfinance", "twelvedata", "tiingo", "tigeropen"
    );

    private final StockDataSourcePriorityRepository repository;

    public StockDataSourcePriorityService(StockDataSourcePriorityRepository repository) {
        this.repository = repository;
    }

    /**
     * 获取某支股票的补填优先级列表（数据源名称按顺序）。
     * 有历史成功记录的先排（按 last_success_time DESC），
     * 无记录的使用默认顺序。
     */
    public List<String> getPriorityList(String symbol) {
        List<StockDataSourcePriority> records =
                repository.findBySymbolOrderByLastSuccessTimeDesc(symbol);

        if (records.isEmpty()) {
            return new ArrayList<>(DEFAULT_DATA_SOURCE_ORDER);
        }

        Set<String> successSources = records.stream()
                .map(StockDataSourcePriority::getDataSource)
                .collect(Collectors.toSet());

        List<String> result = new ArrayList<>();
        for (StockDataSourcePriority r : records) {
            result.add(r.getDataSource());
        }
        for (String ds : DEFAULT_DATA_SOURCE_ORDER) {
            if (!successSources.contains(ds)) {
                result.add(ds);
            }
        }
        return result;
    }

    /** 查询某支股票的历史成功记录（含时间信息） */
    public List<StockDataSourcePriority> getPriorityRecords(String symbol) {
        return repository.findBySymbolOrderByLastSuccessTimeDesc(symbol);
    }

    /** 查询所有记录（按 symbol 升序） */
    public List<StockDataSourcePriority> getAllRecords() {
        return repository.findAll(Sort.by(Sort.Direction.ASC, "symbol"));
    }

    /**
     * 更新某支股票某数据源的成功时间（P2-3）。
     * <p>改为"存在则更新、不存在则插入"，不再"删后插"——
     * 原删除+插入非原子，并发调用互相覆盖或触发唯一约束冲突。
     * 并发兜底：(symbol, data_source) 唯一约束，插入冲突时捕获后重试一次按更新处理。</p>
     */
    @Transactional
    public void updatePriority(String symbol, String dataSource, LocalDateTime successTime) {
        try {
            upsertOnce(symbol, dataSource, successTime);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发插入冲突：另一线程已插入该 (symbol, data_source)，按更新处理
            log.debug("[DataSourcePriority] concurrent insert conflict, retry as update: symbol={}, dataSource={}",
                    symbol, dataSource);
            upsertOnce(symbol, dataSource, successTime);
        }
    }

    private void upsertOnce(String symbol, String dataSource, LocalDateTime successTime) {
        Optional<StockDataSourcePriority> existing =
                repository.findBySymbolAndDataSource(symbol, dataSource);
        if (existing.isPresent()) {
            existing.get().setLastSuccessTime(successTime);
            repository.save(existing.get());
            log.debug("[DataSourcePriority] updated: symbol={}, dataSource={}, time={}",
                    symbol, dataSource, successTime);
            return;
        }
        StockDataSourcePriority record = StockDataSourcePriority.of(symbol, dataSource, successTime);
        repository.save(record);
        log.debug("[DataSourcePriority] inserted: symbol={}, dataSource={}, time={}",
                symbol, dataSource, successTime);
    }
}
