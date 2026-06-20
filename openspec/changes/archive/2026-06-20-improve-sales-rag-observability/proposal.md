## Why

销售报表 RAG 已经是 Chat2Excel 当前最有价值的 AI 能力之一，但它的预期行为仍分散在代码注释和多份说明文档里。我们需要用一条正式的 OpenSpec 变更，把后续 RAG 升级的检索、排序、SSE 展示、缓存失效等验收标准先写清楚，避免每次都从实现细节里重新猜项目意图。

## What Changes

- 把销售报表 RAG 定义为一个由长期规格维护的系统能力。
- 规范检索上下文、候选表排序、SQL 生成辅助、SSE 进度展示和降级提示的行为。
- 让缓存失效和 pgvector 索引复用具备足够可观测性，便于调试，也便于用户信任结果。
- 写清楚这条变更归档进长期 `sales-rag` 规格前需要完成的实现任务。

## Capabilities

### New Capabilities

- `sales-rag`: 覆盖面向销售报表的文件级 RAG，包括 pgvector 索引、语义与关键词混合检索、候选表排序、Prompt 上下注入、SSE 阶段可见性，以及旧索引防护。

### Modified Capabilities

- 暂无。这是现有销售报表 RAG 行为的第一条 OpenSpec 能力规格。

## Impact

- 影响的后端区域：`ai-service`，尤其是 `RagServiceImpl`、`PgVectorRagVectorStoreServiceImpl`、`AiServiceImpl`、`AiModelServiceImpl`、`RagContext` 和 `ProcessStage`。
- 影响的配置：`chat2excel.rag.*`、`chat2excel.pgvector.*`，以及通过 `ai-service/src/main/resources/bootstrap-local.yml` 进行的本地验证链路。
- 影响的文档/规格：`openspec/changes/improve-sales-rag-observability` 下的新 OpenSpec 变更文件，以及后续 `openspec/specs/sales-rag` 下的长期规格。
- 本提案不计划引入破坏性 API 变更。
