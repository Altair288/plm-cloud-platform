package com.plm.infrastructure.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 多数据源 + 多 Flyway 配置。
 * plm-meta 数据源用于元数据/编码规则；plm 运行时数据源用于用户/分类实例等。
 * 通过不同的迁移位置分别执行。
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
	@ConditionalOnProperty(prefix = "plm.flyway.meta", name = "enabled", havingValue = "true", matchIfMissing = true)
	@ConfigurationProperties("plm.datasource.meta")
	public DataSourceProperties metaDataSourceProperties() {
		return new DataSourceProperties();
	}

	@Bean(name = "metaDataSource")
	public DataSource metaDataSource(@Qualifier("metaDataSourceProperties") DataSourceProperties props) {
		return props.initializeDataSourceBuilder().build();
	}

	@Bean
	@ConfigurationProperties("plm.datasource.runtime")
	public DataSourceProperties runtimeDataSourceProperties() {
		return new DataSourceProperties();
	}

	@Bean(name = "runtimeDataSource")
	public DataSource runtimeDataSource(@Qualifier("runtimeDataSourceProperties") DataSourceProperties props) {
		return props.initializeDataSourceBuilder().build();
	}

	// ========== Transaction Managers ==========
	@Bean
	@ConditionalOnMissingBean(name = "metaTxManager")
	public DataSourceTransactionManager metaTxManager(@Qualifier("metaDataSource") DataSource ds) {
		return new DataSourceTransactionManager(ds);
	}

	@Bean
	@ConditionalOnMissingBean(name = "runtimeTxManager")
	public DataSourceTransactionManager runtimeTxManager(@Qualifier("runtimeDataSource") DataSource ds) {
		return new DataSourceTransactionManager(ds);
	}

	// ========== Flyway beans (manual bootstrap) ==========
	@Bean(name = "metaFlyway")
	public Flyway metaFlyway(@Qualifier("metaDataSource") DataSource ds) {
		// 对应资源路径: src/main/resources/db/migration/meta
		Flyway flyway = Flyway.configure()
				.dataSource(ds)
				.schemas("plm_meta") // 只关注 plm_meta schema，避免 public 非空导致 baseline 跳过 V1
				.locations("classpath:db/migration/meta")
				.baselineOnMigrate(false) // 初次迁移不自动 baseline，确保 V1 执行
				.placeholderReplacement(false) // 禁用占位符解析，防止注释内模板被识别
				.failOnMissingLocations(true)
				.table("flyway_schema_history")
				.load();
		flyway.migrate();
		return flyway;
	}

	@Bean(name = "runtimeFlyway")
	public Flyway runtimeFlyway(@Qualifier("runtimeDataSource") DataSource ds) {
		// 对应资源路径: src/main/resources/db/migration/runtime
		Flyway flyway = Flyway.configure()
				.dataSource(ds)
				.schemas("plm")
				.locations("classpath:db/migration/runtime")
				.baselineOnMigrate(false)
				.placeholderReplacement(false)
				.failOnMissingLocations(true)
				.table("flyway_schema_history")
				.load();
		flyway.migrate();
		return flyway;
	}
}
