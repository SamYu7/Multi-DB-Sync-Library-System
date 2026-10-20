package com.library.config;

public class DataSourceConfig {

    public enum DataSourceType {
        MYSQL("mysql"),
        POSTGRESQL("postgresql"),
        SQL_SERVER("sqlserver");

        private final String name;

        DataSourceType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }
}