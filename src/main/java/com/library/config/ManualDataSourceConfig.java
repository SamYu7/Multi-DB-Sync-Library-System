package com.library.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class ManualDataSourceConfig {

    // MySQL数据源 - 与application.yml中的配置一致
    @Bean(name = "mysqlDataSource")
    public DataSource mysqlDataSource() {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .url("jdbc:mysql://localhost:3306/library_system?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8")
                .username("app_user")
                .password("userpassword")
                .build();
    }

    // PostgreSQL数据源
    @Bean(name = "postgresqlDataSource")
    public DataSource postgresqlDataSource() {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url("jdbc:postgresql://localhost:5432/library_system")
                .username("postgres")
                .password("postgrespassword")
                .build();
    }

    // SQL Server数据源
    @Bean(name = "sqlserverDataSource")
    public DataSource sqlserverDataSource() {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver")
                .url("jdbc:sqlserver://localhost:1433;databaseName=library_system;encrypt=true;trustServerCertificate=true")
                .username("sa")
                .password("SqlServer123!")
                .build();
    }

    // 主数据源 - 使用动态路由
    @Bean
    @Primary  // 关键：标记为主要数据源
    public DataSource dynamicDataSource() {
        // 创建目标数据源映射
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("mysql", mysqlDataSource());
        targetDataSources.put("postgresql", postgresqlDataSource());
        targetDataSources.put("sqlserver", sqlserverDataSource());

        // 创建路由数据源
        SimpleRoutingDataSource routingDataSource = new SimpleRoutingDataSource();
        routingDataSource.setDefaultTargetDataSource(mysqlDataSource());  // 默认数据源
        routingDataSource.setTargetDataSources(targetDataSources);

        // 初始化
        routingDataSource.afterPropertiesSet();

        return routingDataSource;
    }
}