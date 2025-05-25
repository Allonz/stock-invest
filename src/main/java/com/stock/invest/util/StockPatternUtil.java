package com.stock.invest.util;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stock.invest.model.KLineData;

/**
 * 股票模式识别工具类
 * 提供各种识别股票交易模式的通用方法
 */
public class StockPatternUtil {
    
    private static final Logger log = LoggerFactory.getLogger(StockPatternUtil.class);
    
    /**
     * 检查K线数据是否符合特定的交易量模式
     * 条件：
     * 1. 前两日平均成交量大于前一日成交量
     * 2. 前三日平均成交量大于前两日平均成交量
     * 3. 前四日平均成交量大于前三日平均成交量
     * 4. 前五日平均成交量大于前四日平均成交量
     * 5. 前六日平均成交量大于前五日平均成交量
     * 
     * @param klineData K线数据列表，按时间降序排列（最新的在前）
     * @return 是否符合模式
     */
    public static boolean matchesVolumePattern(List<Map<String, Object>> klineData) {
        if (klineData == null || klineData.size() < 7) {
            return false;
        }
        
        try {
            // 获取成交量数组
            long[] volumes = new long[klineData.size()];
            for (int i = 0; i < klineData.size(); i++) {
                Object vol = klineData.get(i).get("volume");
                if (vol == null) {
                    log.warn("成交量数据为空，无法进行模式匹配");
                    return false;
                }
                volumes[i] = Long.parseLong(vol.toString());
            }
            
            return checkVolumePattern(volumes);
        } catch (Exception e) {
            log.error("检查交易量模式时发生错误: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 使用KLineData对象检查K线数据是否符合特定的交易量模式
     * @param klineDataList K线数据对象列表
     * @return 是否符合模式
     */
    public static boolean matchesVolumePatternForKLineData(List<KLineData> klineDataList) {
        if (klineDataList == null || klineDataList.size() < 7) {
            return false;
        }
        
        try {
            // 获取成交量数组
            long[] volumes = new long[klineDataList.size()];
            for (int i = 0; i < klineDataList.size(); i++) {
                KLineData data = klineDataList.get(i);
                volumes[i] = data.getVolume();
            }
            
            return checkVolumePattern(volumes);
        } catch (Exception e) {
            log.error("检查交易量模式时发生错误: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 检查交易量数组是否符合模式
     * @param volumes 成交量数组
     * @return 是否符合模式
     */
    private static boolean checkVolumePattern(long[] volumes) {
        if (volumes.length < 7) {
            return false;
        }
        
        // 检查点1：前两日平均成交量大于前一日成交量
        double avg2 = (volumes[1] + volumes[2]) / 2.0;
        if (avg2 <= volumes[0]) {
            return false;
        }
        
        // 检查点2：前三日平均成交量大于前两日平均成交量
        double avg3 = (volumes[1] + volumes[2] + volumes[3]) / 3.0;
        if (avg3 <= avg2) {
            return false;
        }
        
        // 检查点3：前四日平均成交量大于前三日平均成交量
        double avg4 = (volumes[1] + volumes[2] + volumes[3] + volumes[4]) / 4.0;
        if (avg4 <= avg3) {
            return false;
        }
        
        // 检查点4：前五日平均成交量大于前四日平均成交量
        double avg5 = (volumes[1] + volumes[2] + volumes[3] + volumes[4] + volumes[5]) / 5.0;
        if (avg5 <= avg4) {
            return false;
        }
        
        // 检查点5：前六日平均成交量大于前五日平均成交量
        double avg6 = (volumes[1] + volumes[2] + volumes[3] + volumes[4] + volumes[5] + volumes[6]) / 6.0;
        if (avg6 <= avg5) {
            return false;
        }
        
        return true;
    }
} 