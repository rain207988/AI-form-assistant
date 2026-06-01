package com.bitejiuyeke.ai.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 向量库存储的 chunk 结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagVectorChunk {

    private String chunkId;

    private Long fileId;

    private String tableName;

    private String sheetName;

    private String chunkType;

    private String content;

    private String keywords;

    private String canonicalTerms;

    private String businessSummary;

    private String timeSummary;

    private float[] embedding;
}
