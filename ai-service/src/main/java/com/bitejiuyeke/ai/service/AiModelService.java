package com.bitejiuyeke.ai.service;

import java.util.List;
import java.util.Map;

/**
 * AI大模型服务接口
 */
public interface AiModelService {
    String generateAiResponse(String prompt, List<Map<String, Object>> resultData, String ragContext);

    List<String> getFieldsFromUserInput(String userInput, String ragContext);

    String getSql(String userInput, String tableName, List<Map<String, Object>> tableStructure, String ragContext);

    String getUpdateSql(String userInput, String tableName, List<Map<String, Object>> tableStructure, String ragContext);
}
