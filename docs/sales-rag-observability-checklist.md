# 销售报表 RAG 可观测性验收清单

这份清单用于实施 `improve-sales-rag-observability` OpenSpec 变更时做回归检查。它不替代自动化测试，但可以作为本地联调、录屏演示和问题排查时的标准步骤。

## 1. 前置环境

建议使用当前仓库的本地配置启动 `ai-service`：

```powershell
cd D:\Proejct_improvement\AI-form-assistant-remote
docker compose -p bite_excel -f deploy/docker-compose-pgvector.yml up -d
mvn -pl ai-service -am spring-boot:run -Dspring-boot.run.profiles=local
```

如果本机没有 `mvn`，至少要完成 OpenSpec 校验和 Java 静态编译检查；真实业务链路验证可以在有 Maven 或 IDE 的环境中执行。

## 2. 索引构建与复用

目标：验证第一次查询会构建 pgvector 索引，第二次查询会复用索引。

步骤：

1. 上传一个包含销售额、区域、客户、产品、月份等字段的销售报表。
2. 第一次提问：“查询华东区 5 月销售额最高的客户”。
3. 观察 SSE `BUILD_RAG_INDEX` 事件。
4. 使用同一个 `fileId` 再提问：“再看一下华东区 5 月销量最高的产品”。
5. 再次观察 SSE `BUILD_RAG_INDEX` 事件。

验收点：

- 第一次查询的 `metadata.indexAction` 应为 `BUILT`。
- 第二次查询的 `metadata.indexAction` 应为 `REUSED`。
- 两次事件都应包含 `metadata.indexedChunkCount`。
- 服务日志应能看到 `RAG索引构建完成` 或 `RAG索引复用`。

## 3. 多 Sheet 候选表排序

目标：验证多 Sheet 销售文件能输出表级候选排序。

建议测试文件包含这些 Sheet：

- `区域销售`
- `客户销售`
- `产品销售`
- `销售员业绩`

建议问题：

- “华东区 5 月销售额是多少？”
- “哪个客户贡献的 GMV 最高？”
- “按产品统计销量排行”
- “销售员本季度毛利排行”

验收点：

- SSE `RETRIEVE_RAG_CONTEXT` 事件应包含 `metadata.matchedTableNames`。
- SSE `RETRIEVE_RAG_CONTEXT` 事件应包含 `metadata.rankedTables`。
- `detail` 中应能读到候选表排序摘要。
- 最终 SQL 应优先落到 RAG 排名最高且语义合理的动态表。

## 4. 降级路径

目标：验证 RAG 不可用时不会打断 AI 主流程。

建议覆盖：

- 关闭 `chat2excel.rag.enabled`。
- 关闭 `chat2excel.pgvector.enabled`。
- embedding 模型不可用。
- 请求缺少 `fileId`。
- 用户输入为空。
- pgvector 没有返回高相关片段。

验收点：

- SSE 仍然应输出 `BUILD_RAG_INDEX` 和 `RETRIEVE_RAG_CONTEXT`。
- `metadata.indexAction` 应为 `SKIPPED`，或 `metadata.fallbackReason` 能说明降级原因。
- 请求不应只因为 RAG 不可用而失败。
- 日志应能看到 `RAG检索降级`，但不应包含原始样例行内容。

## 5. 修改后索引失效

目标：验证销售数据被修改后，不会继续使用旧向量上下文。

步骤：

1. 对某个销售文件先问一次查询问题，让系统构建索引。
2. 执行修改类问题，例如：“把华南区 3 月销售额修正为 10000”。
3. 修改完成后，对同一个文件再次提问。

验收点：

- 修改流程执行后应调用 `RagService.invalidate(fileId)`。
- 服务日志应出现 `pgvector索引已失效` 和 `pgvector文件索引删除完成`。
- 下一次查询的 `metadata.indexAction` 应回到 `BUILT`。
- 下一次查询不应继续复用修改前的旧 chunk。

## 6. 数据安全检查

目标：确保可观测性信息足够调试，但不泄露业务明细。

验收点：

- SSE `metadata` 可以包含索引状态、chunk 数、命中表名、候选表排序和降级原因。
- SSE `metadata` 不应包含完整 `promptContext`。
- SSE `metadata` 不应直接输出样例行明细。
- 日志不应输出完整 chunk 内容或原始行数据。
