package com.bitejiuyeke.ai.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 流式事件DTO
 */
@Data
@Builder
public class StreamProcessEvent {

    // 事件类型
    private String eventType;

    // 处理阶段
    private String stage;

    // 进度百分比
    private Integer progress;

    // 消息内容
    private String message;

    // 详细消息
    private String detail;

    // 是否完成
    private Boolean completed;

    // 最终的数据结果
    private AiUnifiedResponse result;

    // 错误信息
    private String error;

    // SQL语句
    private String sqlQuery;

    // 查询结果预览
    private List<Map<String, Object>> resultPreview;

    // 查询总数
    private Integer resultCount;

    // AI响应内容
    private String aiResponseContent;

    // 附加结构化信息，例如 RAG 阶段的索引状态、命中表和降级原因
    private Map<String, Object> metadata;

}
