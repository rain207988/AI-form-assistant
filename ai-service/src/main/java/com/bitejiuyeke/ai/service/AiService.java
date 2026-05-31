package com.bitejiuyeke.ai.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.bitejiuyeke.ai.dto.request.AiChatRequest;
import com.bitejiuyeke.ai.dto.request.AiRequestHistoryRequest;
import com.bitejiuyeke.ai.dto.request.SendEmailRequest;
import com.bitejiuyeke.ai.dto.response.AiRequestHistoryResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI服务业务接口
 */
public interface AiService {
    void streamUnifiedChat(AiChatRequest aiChatRequest, Long userId, SseEmitter sseEmitter);

    IPage<AiRequestHistoryResponse> getRequestHistory(AiRequestHistoryRequest aiRequestHistoryRequest, Long userId);

    Boolean sendEmailWithExcel(SendEmailRequest sendEmailRequest);
}
