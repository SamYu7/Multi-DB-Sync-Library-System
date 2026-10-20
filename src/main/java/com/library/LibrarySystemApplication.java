package com.library;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties
public class LibrarySystemApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(LibrarySystemApplication.class);
		// 添加外部配置文件
		app.setAdditionalProfiles("datasource");

		app.run(args);
	}
}