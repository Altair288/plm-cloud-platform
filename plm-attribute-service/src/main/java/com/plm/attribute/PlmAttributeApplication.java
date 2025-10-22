package com.plm.attribute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@ComponentScan(basePackages = {"com.plm.attribute", "com.plm.infrastructure", "com.plm.common"})
@EnableJpaRepositories(basePackages = "com.plm.infrastructure.repository")
@EntityScan(basePackages = "com.plm.common.domain")
@EnableTransactionManagement
public class PlmAttributeApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlmAttributeApplication.class, args);
    }
}
