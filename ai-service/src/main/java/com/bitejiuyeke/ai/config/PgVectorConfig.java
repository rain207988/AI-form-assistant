package com.bitejiuyeke.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * pgvector 数据源配置
 */
@Configuration
@Slf4j
public class PgVectorConfig {

    @Bean(name = "pgVectorDataSource")
    public DataSource pgVectorDataSource(PgVectorProperties pgVectorProperties) {
        return DataSourceBuilder.create()
                .driverClassName("org.postgresql.Driver")
                .url(pgVectorProperties.getUrl())
                .username(pgVectorProperties.getUsername())
                .password(pgVectorProperties.getPassword())
                .build();
    }

    @Bean(name = "pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate(@Qualifier("pgVectorDataSource") DataSource pgVectorDataSource) {
        return new JdbcTemplate(pgVectorDataSource);
    }
}
