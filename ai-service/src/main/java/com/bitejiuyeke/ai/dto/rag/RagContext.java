package com.bitejiuyeke.ai.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * RAG检索上下文
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagContext {

    /**
     * 是否启用RAG
     */
    private boolean enabled;

    /**
     * 是否命中缓存索引
     */
    private boolean cacheHit;

    /**
     * 当前文件总知识片段数
     */
    private int indexedChunkCount;

    /**
     * 当前问题命中的知识片段数
     */
    private int matchedChunkCount;

    /**
     * 命中的表名列表
     */
    private List<String> matchedTableNames;

    /**
     * 表级排序结果，格式为 "tableName(0.8732)"
     */
    private List<String> rankedTables;

    /**
     * 给前端展示的检索摘要
     */
    private String retrieveSummary;

    /**
     * 最终拼入Prompt的上下文
     */
    private String promptContext;

    public static RagContext empty(String summary) {
        return RagContext.builder()
                .enabled(false)
                .cacheHit(false)
                .indexedChunkCount(0)
                .matchedChunkCount(0)
                .matchedTableNames(Collections.emptyList())
                .rankedTables(Collections.emptyList())
                .retrieveSummary(summary)
                .promptContext("")
                .build();
    }

    public boolean hasPromptContext() {
        return promptContext != null && !promptContext.isBlank();
    }
}
