## 1. 基线对齐

- [x] 1.1 对照 `sales-rag` OpenSpec 要求检查 `RagServiceImpl.retrieveContext(...)`，记录现有行为差距。
- [x] 1.2 确认 `RagContext` 能携带索引复用状态、已索引 chunk 数、命中 chunk 数、命中表名、候选表排序摘要、降级摘要和 Prompt 上下文。
- [x] 1.3 确认 `AiServiceImpl.prepareRagContextAndSendProgress(...)` 在成功检索和优雅降级路径下都会输出 `BUILD_RAG_INDEX` 与 `RETRIEVE_RAG_CONTEXT`。
- [x] 1.4 确认数据更新路径在修改后会调用 `RagService.invalidate(fileId)`。

## 2. 可观测性实现

- [x] 2.1 标准化 `BUILD_RAG_INDEX` SSE 数据载荷，清楚展示索引是新建还是复用，以及可用 chunk 数。
- [x] 2.2 标准化 `RETRIEVE_RAG_CONTEXT` SSE 数据载荷，展示命中 chunk 数、命中表名、候选表排序摘要或降级原因。
- [x] 2.3 确保 SSE 摘要不会暴露完整原始 Prompt 上下文或不必要的样例行内容。
- [x] 2.4 为 pgvector 索引构建、复用、检索降级和失效增加结构化日志，日志包含 `fileId`，但不包含敏感行数据。

## 3. 回归检查

- [x] 3.1 增加或记录可重复的冒烟检查，验证第一次查询构建索引、第二次查询复用索引。
- [x] 3.2 增加或记录多 Sheet 销售测试文件，验证区域、客户、产品、销售员、销售额、销量、毛利、月份、季度等查询下的候选表排序。
- [x] 3.3 增加或记录降级检查，覆盖 RAG 关闭、pgvector 关闭、embedding 模型缺失、fileId 缺失和低相关性检索。
- [x] 3.4 增加或记录更新流程检查，证明文件数据被修改后会失效 pgvector chunk，下一次查询会重建索引。

## 4. 文档与校验

- [x] 4.1 更新现有 RAG 文档，让 pgvector 被明确描述为当前基线，并把旧的纯内存索引描述标记为历史内容。
- [x] 4.2 增加一份简短项目说明，解释本仓库如何把 OpenSpec 作为未来 RAG 和 AI 主链路变更的需求闸门。
- [x] 4.3 运行 `npx @fission-ai/openspec validate --all --strict`。
- [x] 4.4 实现和校验全部完成后，把 `improve-sales-rag-observability` 归档进长期 `sales-rag` 规格。
