package com.library.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Slf4j
public class AccessDeniedController {

    @GetMapping("/access-denied")
    public String accessDenied(Model model) {
        log.warn("用户访问了未授权的资源");
        model.addAttribute("title", "访问被拒绝");
        model.addAttribute("message", "抱歉，您没有权限访问此页面。");
        model.addAttribute("advice", "请联系系统管理员获取相应权限。");
        return "error/403";
    }
}