# 📚 多数据库同步管理系统 (Multi-DB Sync Library System)

![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-green)
![MySQL](https://img.shields.io/badge/MySQL-8.0-orange)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue)
![SQL Server](https://img.shields.io/badge/SQL_Server-2019-red)

基于 Spring Boot 的图书管理系统，实现了 MySQL、PostgreSQL、SQL Server 三库实时同步、冲突检测、邮件告警以及 PC/移动端可视化报表。

## 📖 项目文档
- 📄 [实验报告 (PDF)](./docx/多数据库同步管理系统实践实验报告.pdf) - 包含完整的系统设计、E-R图、测试用例与心得体会。
- 📊 [汇报演示文稿 (PPT)](./docx/多数据库同步管理系统实践PPT.pptx) - 包含系统架构、核心代码逻辑与运行效果展示。

## ✨ 核心特性
- **多库实时同步**：无中间件依赖，通过 JdbcTemplate 直连原生 SQL 实现三库同步。
- **智能冲突处理**：自动检测数据差异，提供多策略（源数据/目标数据/最新数据）解决冲突。
- **三级邮件降级**：HTML邮件 -> 文本邮件 -> 日志记录，确保冲突告警 100% 可达。
- **多端可视化**：基于 Chart.js 和 Bootstrap 的 PC/移动端响应式数据统计报表。
- **权限管理**：基于 Spring Security 实现的多角色（管理员/分馆/读者）权限控制。

## 🚀 快速启动
1. 使用 Docker 启动三个数据库：`docker-compose up -d`
2. 导入 `/sql` 目录下的初始化脚本。
3. 修改 `src/main/resources/application.yml.example` 为 `application.yml`，并填入你的数据库和邮箱配置。
4. 运行 Spring Boot 启动类，访问 `http://localhost:8080/library`

> **Note**: 本项目为中南大学《数据库系统实践》课程项目。
