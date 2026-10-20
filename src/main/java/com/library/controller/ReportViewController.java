package com.library.controller;

import com.library.service.MultiDatabaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/reports")
public class ReportViewController {

    @Autowired
    private MultiDatabaseService multiDatabaseService;

    /**
     * 报表主页面
     */
    @GetMapping("/view")
    public String reportView(Model model) {
        // 获取今日统计
        var stats = multiDatabaseService.getTodayConflictStats();
        model.addAttribute("stats", stats);
        model.addAttribute("reportDate", LocalDate.now().toString());
        model.addAttribute("applicationName", "图书馆多数据库同步系统");

        return "report";
    }

    @GetMapping("/mobile-view")
    public String mobileReportView(Model model) {
        // ========== 使用模拟数据，完全绕过Service ==========
        Map<String, Object> mockStats = new HashMap<>();
        mockStats.put("success", true);
        mockStats.put("totalConflicts", 42);
        mockStats.put("unresolvedCount", 15);
        mockStats.put("resolvedCount", 27);
        mockStats.put("threeDbConflicts", 8);

        Map<String, Integer> mockTableStats = new HashMap<>();
        mockTableStats.put("books", 25);
        mockTableStats.put("book_copies", 10);
        mockTableStats.put("users", 5);
        mockTableStats.put("borrow_records", 2);
        mockStats.put("tableStats", mockTableStats);

        model.addAttribute("stats", mockStats);
        model.addAttribute("isMobile", true);
        model.addAttribute("reportDate", "2024-01-15");

        return "mobile-report";
    }
    /**
     * 冲突详情页面
     */
    @GetMapping("/detail/{conflictId}")
    public String conflictDetail(@PathVariable String conflictId, Model model) {
        var status = multiDatabaseService.getConflictStatus(conflictId);
        model.addAttribute("conflict", status.get("conflict"));
        model.addAttribute("stillHasConflict", status.get("stillHasConflict"));

        return "conflict-detail";
    }
}