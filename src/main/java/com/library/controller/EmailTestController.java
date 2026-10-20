package com.library.controller;

import com.library.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/email")
@Slf4j
public class EmailTestController {

    @Autowired
    private EmailService emailService;

    /**
     * 发送测试邮件（支持GET和POST）
     */
    @RequestMapping(value = "/test", method = {RequestMethod.GET, RequestMethod.POST})
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public Map<String, Object> sendTestEmail(
            @RequestParam(required = false) String email,
            @RequestBody(required = false) Map<String, String> body) {

        Map<String, Object> result = new HashMap<>();

        try {
            // 从参数或请求体获取邮箱
            String targetEmail;
            if (email != null && !email.trim().isEmpty()) {
                targetEmail = email.trim();
            } else if (body != null && body.containsKey("email")) {
                targetEmail = body.get("email").trim();
            } else {
                targetEmail = "1011159957@qq.com"; // 默认测试邮箱
            }

            log.info("📧 发送测试邮件到: {}", targetEmail);

            // 发送测试邮件
            emailService.sendTestEmail(targetEmail);

            result.put("success", true);
            result.put("message", "测试邮件发送请求已提交");
            result.put("targetEmail", targetEmail);
            result.put("timestamp", new Date());

        } catch (Exception e) {
            log.error("发送测试邮件失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 邮件服务状态检查（GET请求）
     */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('LIB_ADMIN', 'SYS_ADMIN')")
    public Map<String, Object> getEmailStatus() {
        Map<String, Object> result = new HashMap<>();

        try {
            result.put("success", true);
            result.put("status", "邮件服务已就绪");
            result.put("enabled", true);
            result.put("service", "EmailService");
            result.put("timestamp", new Date());
            result.put("note", "请使用 POST 方法发送测试邮件");

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }
}