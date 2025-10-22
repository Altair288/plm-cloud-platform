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
 * 单数据源 + 双 Flyway 配置。
 * 现在将原 plm_meta 与 plm 合并到一个数据库（同一个 URL）。
 * 仍然按照不同 schema (plm_meta, plm) 与不同迁移路径拆分逻辑。
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
			.schemas("plm_meta", "plm")
			.locations("classpath:db/migration")
			.placeholderReplacement(false)
			.baselineOnMigrate(false)
			.table("flyway_schema_history")
			.load();
		flyway.migrate();
		return flyway;
	}
}
