package com.plm.attribute.version.service.workbook;

import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
public class WorkbookImportAsyncConfig {

    @Bean(name = "workbookImportTaskExecutor")
    public Executor workbookImportTaskExecutor(WorkbookImportProperties properties) {
        WorkbookImportProperties.Async async = properties.getAsync();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(async.getThreadNamePrefix());
        executor.setCorePoolSize(async.getCorePoolSize());
        executor.setMaxPoolSize(async.getMaxPoolSize());
        executor.setQueueCapacity(async.getQueueCapacity());
        executor.initialize();
        return executor;
    }
}