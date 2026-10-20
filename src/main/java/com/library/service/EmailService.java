package com.library.service;

import com.library.entity.DataConflict;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class EmailService {

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${spring.mail.port:0}")
    private int mailPort;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    // 不需要 mailPassword，Spring会自己处理

    @Value("${library.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${library.email.admin-email:}")
    private String adminEmail;

    @Value("${library.email.from-email:}")
    private String fromEmail;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @PostConstruct
    public void init() {
        log.info("📧 ========== 邮件配置检查 ==========");
        log.info("📧 启用状态: {}", emailEnabled);
        log.info("📧 SMTP主机: {}", mailHost);
        log.info("📧 端口: {}", mailPort);
        log.info("📧 用户名: {}", mailUsername);
        log.info("📧 管理员邮箱: {}", adminEmail);
        log.info("📧 发件人邮箱: {}", fromEmail);
        log.info("📧 MailSender实例: {}", mailSender != null ? "已创建" : "null");

        // 检查环境变量
        String envPassword = System.getenv("MAIL_PASSWORD");
        String sysPassword = System.getProperty("MAIL_PASSWORD");
        log.info("📧 环境变量 MAIL_PASSWORD: {}", envPassword != null ? "已设置" : "未设置");
        log.info("📧 系统属性 MAIL_PASSWORD: {}", sysPassword != null ? "已设置" : "未设置");
        log.info("📧 =================================");
    }


    @Autowired(required = false)
    private TemplateEngine templateEngine;

    @Value("${library.email.conflict-notification-enabled:true}")
    private boolean conflictNotificationEnabled;

    @Value("${library.email.conflict-subject:[图书管理系统] 数据同步冲突告警}")
    private String conflictSubject;

    @Value("${spring.application.name:图书管理系统}")
    private String applicationName;

    /**
     * 发送冲突通知邮件给系统管理员
     */
    @Async
    public void sendConflictNotification(DataConflict conflict) {
        if (!emailEnabled || !conflictNotificationEnabled || mailSender == null) {
            log.warn("邮件功能已禁用或未配置，跳过发送冲突通知");
            log.info("📧 【模拟邮件】数据库同步冲突告警");
            log.info("📧 冲突ID: {}", conflict.getLogId());
            log.info("📧 表名: {}", conflict.getTableName());
            log.info("📧 数据ID: {}", conflict.getDataId());
            log.info("📧 冲突详情: {}", conflict.getConflictDetail());
            log.info("📧 发生时间: {}", conflict.getSyncTime());
            log.info("📧 请立即登录系统处理冲突！");
            return;
        }

        try {
            // 构建邮件内容
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail, applicationName + "系统");
            helper.setTo(adminEmail);
            helper.setSubject(conflictSubject);

            // 使用HTML模板
            Context context = new Context();
            context.setVariable("conflict", conflict);
            context.setVariable("applicationName", applicationName);
            context.setVariable("conflictTime",
                    conflict.getSyncTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            context.setVariable("systemUrl", "http://localhost:8080/library"); // 可以根据实际配置修改

            String htmlContent = templateEngine.process("email/conflict-notification", context);
            helper.setText(htmlContent, true);

            // 发送邮件
            mailSender.send(mimeMessage);
            log.info("✅ 冲突通知邮件已发送给管理员: {}", adminEmail);
            log.info("📧 冲突ID: {}, 表名: {}, 数据ID: {}",
                    conflict.getLogId(), conflict.getTableName(), conflict.getDataId());

        } catch (MessagingException e) {
            log.error("❌ 发送冲突通知邮件失败", e);
            // 失败时记录日志
            logFallbackConflictNotification(conflict);
        } catch (Exception e) {
            log.error("❌ 邮件发送过程中发生未知错误", e);
            logFallbackConflictNotification(conflict);
        }
    }

    /**
     * 简单文本邮件版本（备用）
     */
    private void sendSimpleConflictEmail(DataConflict conflict) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(adminEmail);
            message.setSubject(conflictSubject);

            String textContent = buildConflictEmailContent(conflict);
            message.setText(textContent);

            mailSender.send(message);
            log.info("✅ 简单冲突通知邮件已发送");
        } catch (Exception e) {
            log.error("❌ 发送简单冲突邮件失败", e);
        }
    }

    /**
     * 构建邮件内容
     */
    private String buildConflictEmailContent(DataConflict conflict) {
        StringBuilder content = new StringBuilder();
        content.append("【").append(applicationName).append("】数据同步冲突告警\n\n");
        content.append("⚠️ 系统检测到数据库同步冲突，请及时处理！\n\n");

        content.append("📋 冲突信息：\n");
        content.append("────────────────\n");
        content.append("冲突ID：").append(conflict.getLogId()).append("\n");
        content.append("表名：").append(conflict.getTableName()).append("\n");
        content.append("数据ID：").append(conflict.getDataId()).append("\n");
        content.append("冲突数据库：").append(conflict.getSourceDb())
                .append(" → ").append(conflict.getTargetDb()).append("\n");
        content.append("冲突时间：").append(conflict.getSyncTime()).append("\n");

        content.append("\n📝 冲突详情：\n");
        content.append("────────────────\n");
        content.append(conflict.getConflictDetail()).append("\n");

        content.append("\n🔗 处理链接：\n");
        content.append("────────────────\n");
        content.append("请登录系统处理冲突：\n");
        content.append("http://localhost:8080/library/conflicts-page\n");

        content.append("\n💡 处理建议：\n");
        content.append("────────────────\n");
        content.append("1. 登录系统查看冲突详情\n");
        content.append("2. 根据业务需求选择合适的解决策略\n");
        content.append("3. 及时解决避免数据不一致\n");

        content.append("\n────────────────\n");
        content.append("此邮件由系统自动发送，请勿回复\n");

        return content.toString();
    }

    /**
     * 邮件发送失败时的日志记录
     */
    private void logFallbackConflictNotification(DataConflict conflict) {
        log.error("🚨 系统检测到数据同步冲突！");
        log.error("📋 冲突ID: {}", conflict.getLogId());
        log.error("📊 表名: {}", conflict.getTableName());
        log.error("🔑 数据ID: {}", conflict.getDataId());
        log.error("🔄 冲突数据库: {} → {}", conflict.getSourceDb(), conflict.getTargetDb());
        log.error("⏰ 冲突时间: {}", conflict.getSyncTime());
        log.error("📝 冲突详情:\n{}", conflict.getConflictDetail());
        log.error("🚀 请立即登录系统处理！系统地址: http://localhost:8080/library/conflicts-page");
    }

    /**
     * 发送测试邮件
     */
    public void sendTestEmail(String toEmail) {
        log.info("📧 尝试发送测试邮件到: {}", toEmail);
        log.info("📧 当前配置 - 启用: {}, MailSender: {}",
                emailEnabled, mailSender != null);

        if (!emailEnabled) {
            log.error("❌ 邮件功能被禁用，检查 library.email.enabled 配置");
            return;
        }

        if (mailSender == null) {
            log.error("❌ MailSender 为 null，检查 spring.mail 配置");
            log.error("❌ 可能原因：");
            log.error("❌ 1. spring.mail.password 未设置");
            log.error("❌ 2. spring.mail.host/port 配置错误");
            log.error("❌ 3. 依赖未正确引入");
            return;
        }

        try {
            // 测试邮件服务器连接
            log.info("📧 正在测试邮件服务器连接...");

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject("[图书管理系统] 邮件服务测试");
            message.setText("这是一封测试邮件。\n\n发送时间: " + new Date());

            log.info("📧 开始发送邮件...");
            mailSender.send(message);
            log.info("✅ 测试邮件发送成功！请检查邮箱: {}", toEmail);

        } catch (Exception e) {
            log.error("❌ 发送测试邮件失败", e);
            log.error("❌ 错误类型: {}", e.getClass().getName());
            log.error("❌ 错误信息: {}", e.getMessage());

            // 特定错误处理
            if (e.getMessage().contains("Authentication")) {
                log.error("❌ 认证失败！请检查：");
                log.error("❌ 1. QQ邮箱授权码是否正确");
                log.error("❌ 2. 是否开启了SMTP服务");
                log.error("❌ 3. 授权码是否已过期");
            } else if (e.getMessage().contains("connect")) {
                log.error("❌ 连接失败！请检查：");
                log.error("❌ 1. 端口是否正确（587或465）");
                log.error("❌ 2. 防火墙是否阻止");
                log.error("❌ 3. 网络连接是否正常");
            }
        }
    }

    /**
     * 批量发送冲突摘要报告
     */
    @Async
    public void sendConflictSummaryReport(int totalConflicts, int unresolvedCount,
                                          Map<String, Integer> tableStats) {
        if (!emailEnabled || !conflictNotificationEnabled) {
            return;
        }

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail, applicationName + "系统");
            helper.setTo(adminEmail);
            helper.setSubject("[" + applicationName + "] 数据冲突日报 - " +
                    java.time.LocalDate.now().toString());

            Context context = new Context();
            context.setVariable("applicationName", applicationName);
            context.setVariable("reportDate", java.time.LocalDate.now());
            context.setVariable("totalConflicts", totalConflicts);
            context.setVariable("unresolvedCount", unresolvedCount);
            context.setVariable("resolvedCount", totalConflicts - unresolvedCount);
            context.setVariable("tableStats", tableStats);
            context.setVariable("systemUrl", "http://localhost:8080/library");

            String htmlContent = templateEngine.process("email/conflict-summary", context);
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);
            log.info("✅ 冲突摘要报告已发送给管理员");

        } catch (Exception e) {
            log.error("❌ 发送冲突摘要报告失败", e);
        }
    }

    /**
     * 发送报表邮件
     */
    public void sendReportEmail(String recipient, String date, Map<String, Object> reportData) {
        try {
            Context context = new Context();
            context.setVariable("reportData", reportData);
            context.setVariable("date", date);
            context.setVariable("recipient", recipient);

            String htmlContent = templateEngine.process("email/report-summary", context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(recipient);
            helper.setSubject(String.format("数据同步报表 - %s", date));
            helper.setText(htmlContent, true);

            // 可以附加PDF或Excel报表
            // addReportAttachment(helper, reportData);

            mailSender.send(message);
            log.info("📧 报表邮件已发送给: {}", recipient);

        } catch (Exception e) {
            log.error("发送报表邮件失败", e);
        }
    }

}