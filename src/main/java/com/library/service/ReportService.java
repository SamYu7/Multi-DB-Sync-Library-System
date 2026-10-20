package com.library.service;

import com.library.repository.DataConflictRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ReportService {

    @Autowired
    private DataConflictRepository dataConflictRepository;

    /**
     * 获取每日同步统计报表
     */
    public Map<String, Object> getDailySyncReport(LocalDate date) {
        Map<String, Object> report = new HashMap<>();

        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();

        // 统计总数
        List<Object[]> dailyStats = dataConflictRepository.countConflictsByDate(start, end);
        report.put("date", date.toString());
        report.put("dailyStats", dailyStats);

        // 按表统计
        List<Object[]> tableStats = dataConflictRepository.countConflictsByTable();
        report.put("tableStats", tableStats);

        // 按数据库对统计
        List<Object[]> dbPairStats = dataConflictRepository.countConflictsByDatabasePair();
        report.put("dbPairStats", dbPairStats);

        // 未解决冲突
        List<?> unresolved = dataConflictRepository.findUnresolvedConflicts();
        report.put("unresolvedCount", unresolved.size());

        return report;
    }

    /**
     * 生成图表数据
     */
    public Map<String, Object> getChartData(int days) {
        Map<String, Object> chartData = new HashMap<>();

        List<Map<String, Object>> lineChartData = new ArrayList<>();
        List<Map<String, Object>> pieChartData = new ArrayList<>();

        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusDays(days);

        // 模拟图表数据（实际应从数据库查询）
        for (int i = 0; i < days; i++) {
            LocalDate day = LocalDate.now().minusDays(i);
            Map<String, Object> dayData = new HashMap<>();
            dayData.put("date", day.toString());
            dayData.put("conflicts", (int)(Math.random() * 10));
            dayData.put("success", (int)(Math.random() * 50));
            dayData.put("failed", (int)(Math.random() * 5));
            lineChartData.add(dayData);
        }

        // 饼图数据
        String[] tables = {"books", "book_copies", "borrow_records", "users"};
        for (String table : tables) {
            Map<String, Object> pieData = new HashMap<>();
            pieData.put("name", table);
            pieData.put("value", (int)(Math.random() * 100));
            pieChartData.add(pieData);
        }

        chartData.put("lineChart", lineChartData);
        chartData.put("pieChart", pieChartData);

        return chartData;
    }
}