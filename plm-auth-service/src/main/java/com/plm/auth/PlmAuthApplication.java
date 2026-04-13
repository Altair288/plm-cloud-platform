package com.plm.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = {"com.plm.auth", "com.plm.infrastructure"})
@ComponentScan(basePackages = {"com.plm.auth", "com.plm.infrastructure", "com.plm.common"})
@EnableJpaRepositories(basePackages = {"com.plm.infrastructure.repository", "com.plm.infrastructure.version.repository"})
@EntityScan(basePackages = {"com.plm.common.domain", "com.plm.common.version.domain"})
@EnableTransactionManagement
public class PlmAuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlmAuthApplication.class, args);
    }
}
