package com.library.controller;

import com.library.config.DataSourceContextHolder;
import com.library.entity.Book;
import com.library.repository.BookRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/test")
@Slf4j
public class TestController {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private DataSourceContextHolder dataSourceContextHolder;

    @GetMapping("/mysql")
    public List<Book> testMySQL() {
        dataSourceContextHolder.setMySQLDataSource();
        try {
            List<Book> books = bookRepository.findAll();
            log.info("✅ MySQL测试成功，查询到 {} 条数据", books.size());
            return books;
        } finally {
            dataSourceContextHolder.clearDataSource();
        }
    }

    @GetMapping("/postgresql")
    public List<Book> testPostgreSQL() {
        dataSourceContextHolder.setPostgreSQLDataSource();
        try {
            List<Book> books = bookRepository.findAll();
            log.info("✅ PostgreSQL测试成功，查询到 {} 条数据", books.size());
            return books;
        } finally {
            dataSourceContextHolder.clearDataSource();
        }
    }

    @GetMapping("/sqlserver")
    public List<Book> testSQLServer() {
        dataSourceContextHolder.setSQLServerDataSource();
        try {
            List<Book> books = bookRepository.findAll();
            log.info("✅ SQL Server测试成功，查询到 {} 条数据", books.size());
            return books;
        } finally {
            dataSourceContextHolder.clearDataSource();
        }
    }

    @GetMapping("/health")
    public String health() {
        return "Library System is running!";
    }
}