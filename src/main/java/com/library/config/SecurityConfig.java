package com.library.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

import java.io.IOException;
import java.util.Collection;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 权限配置 - 按优先级从高到低
                .authorizeHttpRequests(auth -> auth
                        // 1. 公开访问的资源（必须放在最前面）
                        .requestMatchers(
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/webjars/**",
                                "/favicon.ico",
                                "/error"
                        ).permitAll()

                        // 2. 公开访问的API（测试、健康检查等）
                        .requestMatchers(
                                "/test/**",
                                "/health"
                        ).permitAll()

                        // 3. 登录和首页（不需要认证）
                        .requestMatchers(
                                "/",
                                "/login",
                                "/login/**"
                        ).permitAll()

                        // 4. 仅系统管理员可访问（最高权限）
                        .requestMatchers(
                                "/library/api/conflicts/**",           // 冲突管理API
                                "/library/conflict-resolution/**",     // 冲突解决
                                "/library/conflict-management/**",
                                "/api/conflicts/resolve",           // 解决冲突
                                "/conflict-resolution/resolve",     // 解决冲突
                                "/conflict-management/resolve",     // 解决冲突
                                "/system/**",                       // 系统管理
                                "/users/management/**"              // 用户管理
                        ).hasRole("SYS_ADMIN")

                        // 5. 管理员（系统管理员 + 分馆管理员）可访问
                        .requestMatchers(
                                "/conflicts-page",
                                "/library/api/conflicts/**",           // 冲突管理API
                                "/library/conflict-resolution/**",     // 冲突解决
                                "/library/conflict-management/**",
                                // 冲突管理页面
                                "/api/conflicts/**",                // 冲突管理API（除resolve外）
                                "/conflict-resolution/**",          // 冲突解决查看
                                "/reports/**",                      // 报表查看
                                "/sync/manual/**",                  // 手动同步控制
                                "/conflict-management/**"           // 冲突管理（除resolve外）
                        ).hasAnyRole("SYS_ADMIN", "LIB_ADMIN")

                        // 6. 普通读者只允许访问图书列表页面
                        .requestMatchers(
                                "/library/book-list"   ,             // 图书列表页面（只读）
                                "/borrow/**"                       // BorrowController的所有接口
                        ).hasAnyRole("READER", "LIB_ADMIN", "SYS_ADMIN")

                        // 7. 管理员可访问的图书管理系统
                        .requestMatchers(
                                "/books",                           // 图书管理系统主页
                                "/books/**",                        // 所有图书API操作
                                "/profile/**",                      // 个人资料
                                "/borrow/**",                       // 借阅功能
                                "/test-conflict",                   // 冲突测试
                                "/test-conflict/test-simple-ds",    // 测试数据源
                                "/test-conflict/direct-db-comparison" // 数据库对比
                        ).hasAnyRole("LIB_ADMIN", "SYS_ADMIN")

                        // 8. 分馆管理员额外权限
                        .requestMatchers(
                                "/books/batch",                     // 批量删除图书
                                "/books/delete-from/**",            // 从特定库删除
                                "/sync/trigger",                    // 触发同步
                                "/books/sync/**"                    // 图书同步
                        ).hasAnyRole("LIB_ADMIN", "SYS_ADMIN")

                        // 9. 默认规则：其他所有请求需要认证
                        .anyRequest().authenticated()
                )

                // 表单登录配置 - 修改为根据角色跳转不同页面
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .successHandler(customAuthenticationSuccessHandler()) // 使用自定义成功处理器
                        .failureUrl("/login?error=true")
                        .permitAll()
                )

                // 退出登录配置（保持不变）
                .logout(logout -> logout
                        .logoutRequestMatcher(new AntPathRequestMatcher("/logout"))
                        .logoutSuccessUrl("/login?logout=true")
                        .permitAll()
                )

                // 记住我功能（可选）
                .rememberMe(remember -> remember
                        .key("librarySystemKey")
                        .tokenValiditySeconds(86400)
                )

                // 添加403权限不足页面
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedPage("/access-denied")
                )

                // 禁用CSRF（开发环境方便测试）
                .csrf(csrf -> csrf.disable());

        return http.build();
    }

    // 自定义认证成功处理器
    @Bean
    public AuthenticationSuccessHandler customAuthenticationSuccessHandler() {
        return new SimpleUrlAuthenticationSuccessHandler() {
            @Override
            protected void handle(HttpServletRequest request, HttpServletResponse response,
                                  org.springframework.security.core.Authentication authentication) throws IOException {

                Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
                String targetUrl = "/books"; // 默认跳转到图书管理系统

                // 检查用户角色，决定跳转页面
                for (GrantedAuthority authority : authorities) {
                    String role = authority.getAuthority();
                    if ("ROLE_READER".equals(role)) {
                        // 普通读者跳转到图书列表页面
                        targetUrl = "/book-list";
                        break;
                    } else if ("ROLE_LIB_ADMIN".equals(role) || "ROLE_SYS_ADMIN".equals(role)) {
                        // 管理员跳转到图书管理系统
                        targetUrl = "/books";
                        break;
                    }
                }

                // 执行跳转
                getRedirectStrategy().sendRedirect(request, response, targetUrl);
            }
        };
    }

    // 用户配置保持不变
    @Bean
    public UserDetailsService userDetailsService() {
        // 系统管理员 - 拥有所有权限
        UserDetails admin = User.withDefaultPasswordEncoder()
                .username("admin")
                .password("admin123")
                .roles("SYS_ADMIN", "LIB_ADMIN", "READER")  // 拥有所有角色
                .build();

        // 分馆管理员 - 可以管理图书和冲突，但不能管理系统
        UserDetails libAdmin = User.withDefaultPasswordEncoder()
                .username("libadmin")
                .password("lib123")
                .roles("LIB_ADMIN", "READER")  // 不能访问系统管理
                .build();

        // 普通读者 - 只能查看图书列表页面
        UserDetails reader = User.withDefaultPasswordEncoder()
                .username("reader")
                .password("reader123")
                .roles("READER")  // 只能访问图书列表页面
                .build();

        return new InMemoryUserDetailsManager(admin, libAdmin, reader);
    }
}