package com.bitejiuyeke.ai.service;

import com.bitejiuyeke.ai.dto.rag.RagContext;

/**
 * RAG服务
 */
public interface RagService {

    /**
     * 根据当前文件和用户问题构建检索上下文
     *
     * @param fileId 当前文件ID
     * @param userInput 用户问题
     * @return RAG上下文
     */
    RagContext retrieveContext(Long fileId, String userInput);
}
