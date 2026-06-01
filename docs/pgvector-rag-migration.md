# Chat2Excel pgvector 版 RAG 改造说明

## 1. 这次改造解决的核心问题

之前项目里的 RAG 已经有向量化，但本质上还是：

- 生成 chunk
- 调 embedding
- 把向量挂到 Java 内存对象上
- 用 `ConcurrentHashMap` 做文件级缓存

这会有几个明显限制：

1. 服务重启后索引全部丢失
2. 无法真正称为企业级向量库方案
3. 多实例部署时各实例索引不一致
4. 修改后虽然能失效缓存，但不能做统一持久化管理

这次改造的目标就是把它切成：

**MySQL 做业务数据源，PostgreSQL + pgvector 做持久化向量索引库**

---

## 2. 为什么选 pgvector

这次在“Redis / pgvector / Milvus”里最终落的是 `pgvector`，原因是：

1. 它是当前企业里很常见、很务实的方案
2. 对你这种“文件级 RAG + 销售报表 + 元数据过滤”很合适
3. 比纯内存索引更稳
4. 比单独再上 Milvus 的部署心智成本低
5. 很适合按 `fileId / tableName / chunkType` 做过滤和重建

对 Chat2Excel 这种场景来说，它的定位不是“超大规模公共知识库”，而是：

- 当前文件的结构化知识索引
- 多轮会话内稳定复用
- 修改后可删除并重建

这和 pgvector 很匹配。

---

## 3. 改了哪些文件

### 3.1 `ai-service/pom.xml`

新增：

- `org.postgresql:postgresql`

作用：

- 给 `ai-service` 增加 PostgreSQL 驱动
- 用于连接 pgvector 数据库

### 3.2 `ai-service/src/main/java/com/bitejiuyeke/ai/config/PgVectorProperties.java`

新增 pgvector 配置模型，主要包含：

- `enabled`
- `url`
- `username`
- `password`
- `tableName`
- `dimensions`
- `candidateLimit`

作用：

- 把向量库连接信息和索引参数配置化
- 方便后续在 Nacos 或不同环境中切换

### 3.3 `ai-service/src/main/java/com/bitejiuyeke/ai/config/PgVectorConfig.java`

新增：

- 独立的 `pgVectorDataSource`
- 独立的 `pgVectorJdbcTemplate`

作用：

- 不污染现有 MySQL 业务数据源
- 让向量库存储和业务库职责分离

### 3.4 `ai-service/src/main/java/com/bitejiuyeke/ai/dto/rag/RagVectorChunk.java`

新增向量库存储 DTO，字段包含：

- `chunkId`
- `fileId`
- `tableName`
- `sheetName`
- `chunkType`
- `content`
- `keywords`
- `canonicalTerms`
- `businessSummary`
- `timeSummary`
- `embedding`

作用：

- 把原本内存里的 chunk 结构转成可持久化对象

### 3.5 `ai-service/src/main/java/com/bitejiuyeke/ai/service/RagVectorStoreService.java`

新增向量库服务接口，提供：

- `ensureSchema()`
- `deleteByFileId(fileId)`
- `saveChunks(chunks)`
- `existsByFileId(fileId)`
- `countByFileId(fileId)`
- `similaritySearch(fileId, queryEmbedding, limit)`

作用：

- 把 RAG 检索逻辑和 pgvector 读写逻辑解耦

### 3.6 `ai-service/src/main/java/com/bitejiuyeke/ai/service/impl/PgVectorRagVectorStoreServiceImpl.java`

新增 pgvector 具体实现。

做了几件事：

1. 自动初始化 `vector` extension
2. 自动创建向量表 `rag_vector_chunks`
3. 自动创建：
   - `file_id` 普通索引
   - `embedding` 的 `hnsw` 向量索引
4. 提供按 `fileId` 删除、批量 upsert、文件级向量检索

### 3.7 `ai-service/src/main/java/com/bitejiuyeke/ai/service/impl/RagServiceImpl.java`

这是这次最大的改造点。

它已经从：

- 内存索引
- 本地 cosine 计算
- `ConcurrentHashMap<Long, FileKnowledgeIndex>`

切换成：

- pgvector 持久化索引
- 先按 `fileId` 检索候选 chunk
- 再在 Java 侧做销售语义增强、关键词打分、表级聚合

也就是说现在的结构变成：

1. 构建 chunk
2. embedding
3. 写入 pgvector
4. 查询时从 pgvector 拿回候选 chunk
5. 再做销售报表特化重排

### 3.8 `ai-service/src/main/resources/bootstrap.yml`

新增 `chat2excel.pgvector` 默认配置：

- `enabled`
- `url`
- `username`
- `password`
- `table-name`
- `dimensions`
- `candidate-limit`

### 3.9 `deploy/docker-compose-pgvector.yml`

新增 pgvector 的 docker compose 文件。

作用：

- 方便你本地或测试环境直接起 PostgreSQL + pgvector

### 3.10 `deploy/pgvector/init/01-init-pgvector.sql`

新增初始化 SQL：

- `create extension if not exists vector;`

作用：

- 确保容器初始化时自动启用 pgvector 扩展

---

## 4. 现在的 RAG 链路变成了什么

现在一次 RAG 查询的链路大致是：

1. 用户提问
2. `RagServiceImpl.retrieveContext(fileId, userInput)`
3. 检查当前文件在 pgvector 中是否已有索引
4. 如果没有：
   - 读取文件元数据
   - 构建销售报表 chunk
   - 调 embedding
   - 写入 pgvector
5. 对用户问题做 embedding
6. 按 `fileId` 从 pgvector 做相似检索
7. 把候选 chunk 拉回 Java
8. 在 Java 侧继续做：
   - 销售术语归一化
   - 关键词匹配
   - 表级聚合打分
9. 生成最终 `RagContext`
10. 注入 Prompt，继续走选表、SQL 生成、执行

---

## 5. 为什么不是“全检索逻辑都丢给 pgvector”

这次没有把所有打分都塞进 PostgreSQL 做，原因是：

1. 销售报表语义增强逻辑比较业务化
2. 术语别名、表级聚合、时间摘要这些逻辑在 Java 里更好维护
3. pgvector 更适合做：
   - 持久化存储
   - ANN 相似检索
4. 业务规则重排还是放在服务层更灵活

所以现在的设计是：

- **pgvector 负责“召回”**
- **Java 服务负责“业务重排”**

这对你当前项目是比较平衡的方案。

---

## 6. 修改数据后现在怎么处理

修改流程里原来只是：

- 执行 update
- 清理内存缓存

现在变成：

- 执行 update
- 调 `ragService.invalidate(fileId)`
- 实际上会删除该文件在 pgvector 里的全部 chunk
- 下次再问该文件时自动重建向量索引

这样做的好处是：

- 不会继续用旧向量
- 不会出现“修改后继续追问还是旧报表语义”的问题

---

## 7. 现在向量库存了什么

每个 chunk 都会带这些关键字段：

- `fileId`
- `tableName`
- `sheetName`
- `chunkType`
- `content`
- `keywords`
- `canonicalTerms`
- `businessSummary`
- `timeSummary`
- `embedding`

这意味着以后你如果想继续扩展，还可以做：

- 按 `tableName` 精过滤
- 按 `chunkType` 精过滤
- 对 schema chunk 和 sample chunk 分开检索

---

## 8. 对销售报表场景的实际价值

这次从内存版换到 pgvector 版后，对销售报表最现实的提升是：

1. RAG 索引可以持久化，不再随服务重启丢失
2. 多实例部署时索引更一致
3. 修改后的删除重建链路更清晰
4. 文件级过滤天然适合 Excel 报表场景
5. 后续可以更容易做：
   - 历史文件复用
   - 索引重建任务
   - 运维监控

---

## 9. 部署方式

新增了：

- [docker-compose-pgvector.yml](/D:/Proejct_improvement/Chat2Excel/back_pro/deploy/docker-compose-pgvector.yml)
- [01-init-pgvector.sql](/D:/Proejct_improvement/Chat2Excel/back_pro/deploy/pgvector/init/01-init-pgvector.sql)

启动方式：

```bash
docker compose -p bite_excel -f docker-compose-pgvector.yml up -d
```

默认库名：

- `chat2excel_vector`

默认表名：

- `rag_vector_chunks`

---

## 10. 当前仍需注意的点

这轮改造已经把结构搭起来了，但还有几个现实注意点：

1. `dimensions` 必须和你当前 embedding 模型输出维度一致
   - 现在默认写的是 `1024`
   - 如果 DashScope `text-embedding-v3` 的实际维度不同，需要改配置

2. 当前环境没有 Maven
   - 也没有 `mvnw`
   - 所以无法做完整构建验证

3. 你上线前最好确认：
   - PostgreSQL 容器能正常启 `vector` 扩展
   - `hnsw` 索引在目标 pgvector 版本可用
   - embedding 维度和表定义一致

---

## 11. 建议你接下来怎么验证

建议按这个顺序做：

### 11.1 先验证 pgvector 环境

确认：

- 容器能启动
- 数据库可连通
- `vector` 扩展存在

### 11.2 再验证同一个销售文件的两次提问

第一次：

- 应该触发构建并写入 pgvector

第二次：

- 应该直接复用 pgvector 已有索引

### 11.3 再验证修改后追问

先问：

- “看一下华东区 5 月销售额”

再改：

- “把华东区 5 月销售额修正为最新值”

再问：

- “再看一下修正后的华东区销售额”

观察是否会触发删除后重建索引。

---

## 12. 一句话总结

这次改造把原来“只存在 Java 内存里的销售报表 RAG”，升级成了“**MySQL 做业务数据源，pgvector 做持久化向量检索库，Java 服务继续做销售语义增强和表级重排**”的版本，更适合企业里长期稳定使用。
