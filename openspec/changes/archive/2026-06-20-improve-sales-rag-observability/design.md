## 背景

Chat2Excel 的 AI 主链路已经在 `ai-service` 中接入了面向销售报表的 RAG。当前实现会从文件元数据、Sheet/表映射、字段映射、样例行、销售术语别名、时间/业务摘要中构建文件级 chunk；把 embedding 存入 PostgreSQL pgvector；在 Java 侧对候选片段重排；把 Prompt 上下文注入 AI SQL 生成；并通过 SSE 输出 RAG 相关进度。

现在的主要问题不是“没有 RAG”，而是缺少一份长期稳定的行为契约。相关要求目前分散在实现类和 `docs/sales-rag-optimization.md`、`docs/pgvector-rag-migration.md`、`docs/pgvector-local-validation.md` 等文档里。OpenSpec 应该成为后续修改检索、排序、缓存失效和调试可见性之前的需求闸门。

## 目标 / 非目标

**目标：**

- 为现有销售报表 RAG 行为定义一个一等的 `sales-rag` OpenSpec 能力。
- 让检索、候选表排序、降级、SSE 可见性和缓存失效都具备可测试的验收标准。
- 保持当前架构：MySQL 仍然是 Excel 结构化数据源；pgvector 负责向量召回；Java 服务逻辑负责销售语义重排和 Prompt 拼装。
- 给后续代码和文档对齐提供一份安全的任务清单。

**非目标：**

- 不把 pgvector 替换成 Milvus、Redis Vector 或其他向量数据库。
- 不重设计公开的 `/ai/chat/stream` API 契约。
- 不在本次变更中新增前端 RAG 调试面板。
- 不引入超出现有文件/用户校验范围的企业级租户审计或权限系统。

## 技术决策

1. 将 `sales-rag` 建模为新的 OpenSpec 能力。

   理由：RAG 已经是一个横切行为，会影响检索、选表、SQL 生成、SSE 和数据新鲜度。把它作为独立能力维护，比把它埋在泛 AI 对话文档里更容易评审后续变更。

   备选方案：分别创建 `pgvector-indexing`、`rag-ranking`、`sse-progress` 等规格。这样更细，但对 OpenSpec 第一次落地来说过重。

2. 保持 pgvector 负责召回，Java 负责销售语义重排。

   理由：pgvector 适合作为持久化相似度存储，而现有 Java 逻辑更了解本项目里的销售同义词、维度、指标、时间字段和 Sheet/表映射。

   备选方案：把所有排序都交给 SQL/向量距离。这样代码会更简单，但会失去销售领域权重和表级解释能力。

3. 将 SSE 摘要作为第一层可观测性入口。

   理由：`/ai/chat/stream` 已经会输出 `BUILD_RAG_INDEX` 和 `RETRIEVE_RAG_CONTEXT`。先标准化这些数据载荷，就能在不新增调试接口的情况下，让用户和未来前端调试面板看到关键过程。

   备选方案：立即新增专门的 debug API。这个以后仍可做，但现有 SSE 流是当前摩擦最小的契约。

4. 把降级返回视为成功降级，而不是错误路径。

   理由：缺少 embedding、pgvector 未启用、fileId 为空或检索相关性过低，都不应该直接打断 AI 对话。系统应该返回清晰的降级摘要，并继续走原始 AI 流程。

   备选方案：RAG 不可用时直接让请求失败。这样更严格，但对普通用户体验更差。

## 风险 / 取舍

- [风险] 现有文档中仍有部分内容在描述旧的内存版 RAG。-> 缓解：增加文档更新任务，让长期规格、README 和 RAG 文档都明确 pgvector 是当前基线。
- [风险] 如果 SSE 摘要包含原始 chunk，可能意外暴露过多行数据内容。-> 缓解：流式摘要只展示索引来源、chunk 数量、命中表名和候选表排序摘要，不输出完整 Prompt 上下文。
- [风险] 没有稳定测试夹具时，排序行为不容易验证。-> 缓解：为多 Sheet 销售文件补回归夹具，覆盖已知同义词、时间字段和区域/客户/产品等维度。
- [风险] 更新/导出链路演进时，缓存失效容易回归。-> 缓解：在规格中明确数据变更必须触发失效，并围绕调用 `RagService.invalidate(fileId)` 的更新流程补测试。

## 迁移计划

1. 使用 `npx @fission-ai/openspec validate --all --strict` 校验这条 OpenSpec 变更。
2. 按新的 `sales-rag` 要求对齐代码和文档。
3. 为索引构建/复用、降级、排序、SSE 摘要和缓存失效补充聚焦测试或可重复冒烟检查。
4. 实现任务完成后，归档 `improve-sales-rag-observability`，让 `sales-rag` 要求进入长期规格。

回滚方式比较直接，因为这条变更优先引入的是规划文件。如果后续实现改动导致运行行为回归，可以回滚代码，同时保留或修订 OpenSpec 要求。
