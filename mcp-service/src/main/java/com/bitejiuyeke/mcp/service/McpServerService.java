package com.bitejiuyeke.mcp.service;

import com.bitejiuyeke.mcp.model.McpToolCall;
import com.bitejiuyeke.mcp.model.McpToolResult;
import com.bitejiuyeke.mcp.tool.McpTool;

import java.util.List;

// mcp服务层接口
public interface McpServerService {

    List<McpTool> listTools();

    McpToolResult callTool(McpToolCall mcpToolCall);
}
