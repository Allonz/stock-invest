package com.stock.invest.service.impl;

import com.stock.invest.constant.WindowConstants;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.service.PatternEvaluateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PatternEvaluateServiceImpl implements PatternEvaluateService {

    private static final Logger log = LoggerFactory.getLogger(PatternEvaluateServiceImpl.class);

    @Override
    public boolean matchesIncreasingVolumePattern(List<StockDailyBar> sevenBarsOldestFirst) {
        log.debug("[PatternEval] matchesIncreasingVolumePattern: begin — barCount={}",
                sevenBarsOldestFirst != null ? sevenBarsOldestFirst.size() : 0);
        boolean result = matchesIncreasingVolumePattern(sevenBarsOldestFirst, WindowConstants.MAX_WINDOW_DAYS);
        log.debug("[PatternEval] matchesIncreasingVolumePattern: result={}", result);
        return result;
    }

    @Override
    public boolean matchesIncreasingVolumePattern(List<StockDailyBar> barsOldestFirst, int windowDays) {
        log.debug("[PatternEval] matchesIncreasingVolumePattern: begin — barCount={}, windowDays={}",
                barsOldestFirst != null ? barsOldestFirst.size() : 0, windowDays);

        if (windowDays < WindowConstants.MIN_WINDOW_DAYS || windowDays > WindowConstants.MAX_WINDOW_DAYS) {
            log.debug("[PatternEval] matchesIncreasingVolumePattern: invalid windowDays={}", windowDays);
            return false;
        }
        if (barsOldestFirst == null || barsOldestFirst.size() < windowDays) {
            log.debug("[PatternEval] matchesIncreasingVolumePattern: insufficient data — size={}, required={}",
                    barsOldestFirst != null ? barsOldestFirst.size() : 0, windowDays);
            return false;
        }
        int start = barsOldestFirst.size() - windowDays;
        long[] vol = new long[windowDays];
        for (int i = 0; i < windowDays; i++) {
            StockDailyBar bar = barsOldestFirst.get(start + i);
            if (bar == null || bar.getVolume() == null || bar.getVolume() == 0L) {
                log.debug("[PatternEval] matchesIncreasingVolumePattern: null bar at index={}", i);
                return false;
            }
            vol[i] = bar.getVolume();
        }

        log.debug("[PatternEval] matchesIncreasingVolumePattern: volumes — windowDays={}, lastVolume={}",
                windowDays, vol[windowDays - 1]);

        boolean result = checkIncreasingPattern(vol, windowDays);
        log.debug("[PatternEval] matchesIncreasingVolumePattern: result={}, windowDays={}", result, windowDays);
        return result;
    }

    @Override
    public boolean matchesIncreasingVolumePatternFromKLine(List<KLineIterator> barsOldestFirst, int windowDays) {
        log.debug("[PatternEval] matchesIncreasingVolumePatternFromKLine: begin — barCount={}, windowDays={}",
                barsOldestFirst != null ? barsOldestFirst.size() : 0, windowDays);

        if (windowDays < WindowConstants.MIN_WINDOW_DAYS || windowDays > WindowConstants.MAX_WINDOW_DAYS) {
            log.debug("[PatternEval] matchesIncreasingVolumePatternFromKLine: invalid windowDays={}", windowDays);
            return false;
        }
        if (barsOldestFirst == null || barsOldestFirst.size() < windowDays) {
            log.debug("[PatternEval] matchesIncreasingVolumePatternFromKLine: insufficient data — size={}, required={}",
                    barsOldestFirst != null ? barsOldestFirst.size() : 0, windowDays);
            return false;
        }
        int start = barsOldestFirst.size() - windowDays;
        long[] vol = new long[windowDays];
        for (int i = 0; i < windowDays; i++) {
            KLineIterator bar = barsOldestFirst.get(start + i);
            if (bar == null || bar.getVolume() == 0L) {
                log.debug("[PatternEval] matchesIncreasingVolumePatternFromKLine: null bar at index={}", i);
                return false;
            }
            vol[i] = bar.getVolume();
        }

        log.debug("[PatternEval] matchesIncreasingVolumePatternFromKLine: volumes — windowDays={}, lastVolume={}",
                windowDays, vol[windowDays - 1]);

        boolean result = checkIncreasingPattern(vol, windowDays);
        log.debug("[PatternEval] matchesIncreasingVolumePatternFromKLine: result={}, windowDays={}", result, windowDays);
        return result;
    }

    private static boolean checkIncreasingPattern(long[] vols, int windowDays) {
        log.debug("[PatternEval] checkIncreasingPattern: begin — windowDays={}", windowDays);

        // Build prefix sum for O(1) tail average calculation
        long[] prefixSum = new long[vols.length + 1];
        for (int i = 0; i < vols.length; i++) {
            prefixSum[i + 1] = prefixSum[i] + vols[i];
        }

        for (int len = windowDays; len > 1; len--) {
            double longerAvg = (prefixSum[vols.length] - prefixSum[vols.length - len]) / (double) len;
            double shorterAvg = (prefixSum[vols.length] - prefixSum[vols.length - len + 1]) / (double) (len - 1);
            log.debug("[PatternEval] checkIncreasingPattern: comparing — len={}, longerAvg={}, shorterAvg={}",
                    len, String.format("%.2f", longerAvg), String.format("%.2f", shorterAvg));
            if (!(longerAvg < shorterAvg)) {
                log.debug("[PatternEval] checkIncreasingPattern: failed at len={}, longerAvg >= shorterAvg", len);
                return false;
            }
        }

        double avg2 = (prefixSum[vols.length] - prefixSum[vols.length - 2]) / 2.0;
        double lastVol = vols[windowDays - 1];
        log.debug("[PatternEval] checkIncreasingPattern: final check — avg2={}, lastVol={}", String.format("%.2f", avg2), lastVol);
        boolean result = avg2 < lastVol;
        log.debug("[PatternEval] checkIncreasingPattern: result={}", result);
        return result;
    }

    @Override
    public boolean matchesVolumeSpikePattern(List<StockDailyBar> barsOldestFirst, int windowDays) {
        log.debug("[PatternEval] matchesVolumeSpikePattern: begin - barCount={}, windowDays={}",
                barsOldestFirst != null ? barsOldestFirst.size() : 0, windowDays);

        if (windowDays < WindowConstants.MIN_WINDOW_DAYS
                || windowDays > WindowConstants.MAX_WINDOW_DAYS) {
            log.debug("[PatternEval] matchesVolumeSpikePattern: invalid windowDays={}", windowDays);
            return false;
        }
        if (barsOldestFirst == null || barsOldestFirst.size() < windowDays) {
            log.debug("[PatternEval] matchesVolumeSpikePattern: insufficient data - size={}, required={}",
                    barsOldestFirst != null ? barsOldestFirst.size() : 0, windowDays);
            return false;
        }

        int start = barsOldestFirst.size() - windowDays;
        long[] vol = new long[windowDays];
        for (int i = 0; i < windowDays; i++) {
            StockDailyBar bar = barsOldestFirst.get(start + i);
            if (bar == null || bar.getVolume() == null || bar.getVolume() == 0L) {
                log.debug("[PatternEval] matchesVolumeSpikePattern: null bar at index={}", i);
                return false;
            }
            vol[i] = bar.getVolume();
        }

        long sum = 0;
        for (int i = 0; i < windowDays - 1; i++) {
            sum += vol[i];
        }
        double avgBeforeLast = (double) sum / (windowDays - 1);
        double lastVol = vol[windowDays - 1];
        boolean result = avgBeforeLast * 5.0 < lastVol;

        log.debug("[PatternEval] matchesVolumeSpikePattern: result={}, avgBeforeLast={}, lastVol={}, windowDays={}",
                result, String.format("%.2f", avgBeforeLast), lastVol, windowDays);
        return result;
    }

}
