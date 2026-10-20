package com.library.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

@Configuration
public class MailConfig {

    @Bean
    public JavaMailSender javaMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();

        // 直接硬编码配置（先让邮件能发出去）
        mailSender.setHost("smtp.qq.com");
        mailSender.setPort(465);
        mailSender.setUsername("1011159957@qq.com");
        mailSender.setPassword("sxiaqoyztxembcee");  // 直接设置密码

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", "true");      // 必须
        props.put("mail.smtp.starttls.enable", "true"); // 双重保障
        props.put("mail.smtp.ssl.required", "true");
        props.put("mail.debug", "false");  // 开启debug查看详细过程

        return mailSender;
    }
}