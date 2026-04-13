package com.plm.infrastructure.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "plm.datasource")
public class PlmDataSourceProperties extends DataSourceProperties {
}