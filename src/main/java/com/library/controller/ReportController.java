package com.library.controller;

import com.library.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/reports")
public class ReportController {

    @Autowired
    private ReportService reportService;

    /**
     * 获取日报表
     */
    @GetMapping("/daily")
    public ResponseEntity<Map<String, Object>> getDailyReport(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        if (date == null) {
            date = LocalDate.now();
        }

        Map<String, Object> report = reportService.getDailySyncReport(date);
        return ResponseEntity.ok(report);
    }

    /**
     * 获取图表数据
     */
    @GetMapping("/charts")
    public ResponseEntity<Map<String, Object>> getChartData(
            @RequestParam(defaultValue = "7") int days) {

        Map<String, Object> chartData = reportService.getChartData(days);
        return ResponseEntity.ok(chartData);
    }

    /**
     * 移动端报表接口
     */
    @GetMapping("/mobile")
    public ResponseEntity<Map<String, Object>> getMobileReport() {
        Map<String, Object> report = reportService.getDailySyncReport(LocalDate.now());
        Map<String, Object> chartData = reportService.getChartData(7);

        Map<String, Object> mobileReport = new HashMap<>();
        mobileReport.put("summary", report);
        mobileReport.put("charts", chartData);
        mobileReport.put("timestamp", LocalDate.now().toString());

        return ResponseEntity.ok(mobileReport);
    }
}