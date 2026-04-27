package com.plm.infrastructure.migration;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 独立启动类：仅用于本地/CI 验证双库（plm_meta, plm）迁移是否成功。
 * 启动后 FlywayConfig 中的两个 Flyway Bean 会自动执行 migrate。
 * 运行命令示例（dev profile）：
 *   mvn -pl plm-infrastructure spring-boot:run -Dspring-boot.run.profiles=dev
 */
@SpringBootApplication(scanBasePackages = {"com.plm.infrastructure"})
@ConfigurationPropertiesScan(basePackages = {"com.plm.infrastructure"})
public class PlmMigrationApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(PlmMigrationApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }

    @Bean
    ApplicationRunner migrationExitRunner(ConfigurableApplicationContext context) {
        return args -> {
            int exitCode = SpringApplication.exit(context, () -> 0);
            System.exit(exitCode);
        };
    }
}