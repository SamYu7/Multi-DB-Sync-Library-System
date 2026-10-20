package com.library.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PageController {

    @GetMapping("/")
    public String index() {
        return "redirect:/books";  // 首页重定向到图书页面
    }

    @GetMapping("/login")
    public String loginPage(
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "logout", required = false) String logout,
            Model model) {

        // 添加错误信息
        if (error != null) {
            model.addAttribute("errorMessage", "用户名或密码错误！");
        }

        // 添加退出信息
        if (logout != null) {
            model.addAttribute("logoutMessage", "您已成功退出登录！");
        }

        return "login";  // 返回login.html
    }

    @GetMapping("/conflicts-page")
    public String conflictsPage() {
        return "conflicts";
    }

    @GetMapping("/test-conflict")
    public String testConflictPage() {
        return "test-conflict";
    }


}