package com.bitejiuyeke.ai.service;

import com.bitejiuyeke.ai.dto.rag.RagVectorChunk;

import java.util.List;

/**
 * pgvector 向量存储服务
 */
public interface RagVectorStoreService {

    /**
     * 初始化向量表和索引
     */
    void ensureSchema();

    /**
     * 删除指定文件的全部向量索引
     *
     * @param fileId 文件ID
     */
    void deleteByFileId(Long fileId);

    /**
     * 批量写入 chunk
     *
     * @param chunks 向量 chunk 列表
     */
    void saveChunks(List<RagVectorChunk> chunks);

    /**
     * 当前文件是否已存在向量索引
     *
     * @param fileId 文件ID
     * @return 是否已存在
     */
    boolean existsByFileId(Long fileId);

    /**
     * 统计指定文件的向量条数
     *
     * @param fileId 文件ID
     * @return 条数
     */
    int countByFileId(Long fileId);

    /**
     * 相似度检索
     *
     * @param fileId 文件ID
     * @param queryEmbedding 查询向量
     * @param limit 返回条数
     * @return 候选 chunk
     */
    List<RagVectorChunk> similaritySearch(Long fileId, float[] queryEmbedding, int limit);
}
