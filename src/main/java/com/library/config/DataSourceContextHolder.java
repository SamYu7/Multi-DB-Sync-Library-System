package com.library.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DataSourceContextHolder {

    // 设置MySQL数据源
    public void setMySQLDataSource() {
        log.info("🔀 切换到MySQL数据源");
        SimpleRoutingDataSource.setDataSource("mysql");
    }

    // 设置PostgreSQL数据源
    public void setPostgreSQLDataSource() {
        log.info("🔀 切换到PostgreSQL数据源");
        SimpleRoutingDataSource.setDataSource("postgresql");
    }

    // 设置SQL Server数据源
    public void setSQLServerDataSource() {
        log.info("🔀 切换到SQL Server数据源");
        SimpleRoutingDataSource.setDataSource("sqlserver");
    }

    // 通用设置数据源
    public void setDataSource(String dataSourceName) {
        log.info("🔀 切换到数据源: {}", dataSourceName);
        SimpleRoutingDataSource.setDataSource(dataSourceName);
    }

    // 支持 DataConflict.DBType
    public void setDataSource(com.library.entity.DataConflict.DBType dbType) {
        switch (dbType) {
            case MYSQL:
                setMySQLDataSource();
                break;
            case POSTGRESQL:
                setPostgreSQLDataSource();
                break;
            case SQL_SERVER:
                setSQLServerDataSource();
                break;
            default:
                setMySQLDataSource();
        }
    }

    // 获取当前数据源
    public String getCurrentDataSource() {
        String ds = SimpleRoutingDataSource.getCurrentDataSource();
        log.debug("当前数据源: {}", ds != null ? ds : "默认");
        return ds;
    }

    // 清除数据源
    public void clearDataSource() {
        log.debug("🧹 清除数据源上下文");
        SimpleRoutingDataSource.clearDataSource();
    }

    // 测试模式方法
    public void enterTestMode() {
        log.warn("🚨 进入测试模式");
        clearDataSource();
    }

    public void exitTestMode() {
        log.warn("🚨 退出测试模式");
        clearDataSource();
    }

    // 兼容原有方法
    public Object getActiveTransactionCount() {
        return 0;
    }
}