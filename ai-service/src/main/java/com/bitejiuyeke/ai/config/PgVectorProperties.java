package com.bitejiuyeke.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * pgvector 向量库配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "chat2excel.pgvector")
public class PgVectorProperties {

    /**
     * 是否启用 pgvector
     */
    private boolean enabled = true;

    /**
     * PostgreSQL JDBC URL
     */
    private String url = "jdbc:postgresql://192.168.100.232:5432/chat2excel_vector";

    /**
     * PostgreSQL 用户名
     */
    private String username = "postgres";

    /**
     * PostgreSQL 密码
     */
    private String password = "change-me";

    /**
     * 向量表名
     */
    private String tableName = "rag_vector_chunks";

    /**
     * 向量维度，需与 embedding 模型输出保持一致
     */
    private int dimensions = 1024;

    /**
     * 查询时默认返回候选数量
     */
    private int candidateLimit = 12;
}
