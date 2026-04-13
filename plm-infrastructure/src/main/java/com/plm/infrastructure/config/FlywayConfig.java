package com.plm.infrastructure.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Primary;

/**
 * 单数据源 + 多 schema Flyway 配置。
 * 当前所有 schema 仍落在同一个 PostgreSQL 数据库中。
 * 迁移链已覆盖 plm_meta、旧 plm、plm_platform 与 plm_runtime。
 * 其中 plm 作为历史 runtime 基线暂时保留，新实现逐步向 plm_platform / plm_runtime 收敛。
 *
 * application.yml 属性结构示例：
 * plm:
 *   datasource:
 *     meta:
 *       url: jdbc:postgresql://localhost:5432/plm_meta
 *       username: admin
 *       password: xxx
 *     runtime:
 *       url: jdbc:postgresql://localhost:5432/plm
 *       username: admin
 *       password: xxx
 *   flyway:
 *     meta:
 *       enabled: true
 *       locations: classpath:db/migration/meta
 *     runtime:
 *       enabled: true
 *       locations: classpath:db/migration/runtime
 */
@Configuration
public class FlywayConfig {

	// ========== DataSource Properties ==========
	@Bean
	@ConfigurationProperties("plm.datasource")
	public DataSourceProperties dataSourceProperties() {
		return new DataSourceProperties();
	}

	@Bean(name = "mainDataSource")
	@Primary
	public DataSource mainDataSource(@Qualifier("dataSourceProperties") DataSourceProperties props) {
		return props.initializeDataSourceBuilder().build();
	}

	// ========== Transaction Managers ==========
	@Bean(name = "transactionManager")
	@Primary
	public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
		return new JpaTransactionManager(emf);
	}

	// ========== Flyway beans (manual bootstrap) ==========
	@Bean
	public Flyway flyway(@Qualifier("mainDataSource") DataSource ds) {
		Flyway flyway = Flyway.configure()
			.dataSource(ds)
			.schemas("plm_meta", "plm", "plm_platform", "plm_runtime")
			.locations("classpath:db/migration")
			.placeholderReplacement(false)
			.baselineOnMigrate(false)
			.table("flyway_schema_history")
			.load();
		flyway.migrate();
		return flyway;
	}
}
