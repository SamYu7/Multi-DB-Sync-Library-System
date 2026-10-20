package com.library.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

@Slf4j
public class SimpleRoutingDataSource extends AbstractRoutingDataSource {

    private static final ThreadLocal<String> DATA_SOURCE_KEY = new ThreadLocal<>();

    @Override
    protected Object determineCurrentLookupKey() {
        String key = DATA_SOURCE_KEY.get();
        if (key != null) {
            log.debug("🔀 当前数据源: {}", key);
        }
        return key;
    }

    // 设置数据源
    public static void setDataSource(String dsName) {
        if (dsName != null) {
            log.info("🚀 切换数据源到: {}", dsName);
            DATA_SOURCE_KEY.set(dsName);
        } else {
            clearDataSource();
        }
    }

    // 清除数据源（回到默认）
    public static void clearDataSource() {
        DATA_SOURCE_KEY.remove();
        log.debug("🧹 清除数据源上下文");
    }

    // 获取当前数据源
    public static String getCurrentDataSource() {
        return DATA_SOURCE_KEY.get();
    }
}