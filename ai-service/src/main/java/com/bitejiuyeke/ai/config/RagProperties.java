package com.bitejiuyeke.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG相关配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "chat2excel.rag")
public class RagProperties {

    /**
     * 是否启用RAG
     */
    private boolean enabled = true;

    /**
     * 每次检索返回的片段数量
     */
    private int topK = 4;

    /**
     * 相似度阈值
     */
    private double similarityThreshold = 0.45D;

    /**
     * 每张表最多采样多少行数据来构建知识片段
     */
    private int sampleRowsPerTable = 15;

    /**
     * 每个数据片段放多少行
     */
    private int rowsPerChunk = 5;

    /**
     * 向量索引缓存分钟数
     */
    private long cacheMinutes = 30L;
}
