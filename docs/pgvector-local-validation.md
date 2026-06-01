# Chat2Excel pgvector 本地联调验证说明

## 1. 这份文档的目的

这份文档不是讲设计，而是专门记录这次 `pgvector` 版 RAG 在本地的联调结果，帮助你快速知道：

- 这次又补了哪些内容
- 每个改动起什么作用
- 现在已经验证到哪一步
- 后面你怎么继续验证真实问答链路

---

## 2. 这次新增/补充了什么

### 2.1 新增 `ai-service/src/main/resources/bootstrap-local.yml`

作用：

- 提供一个专门给本地联调使用的 `local` profile
- 默认直连本机 `MySQL / Redis / pgvector`
- 关闭 `Nacos config`、`Nacos discovery`、服务自动注册
- 关闭网关令牌校验，方便本地直接调用接口

这样做之后，本地启动 `ai-service` 不需要再手写很长一串参数。

### 2.2 保留并验证了 pgvector 相关改造

这部分代码已经在之前落地完成，这次联调重点确认它们是否能真正跑起来：

- `PgVectorProperties`
- `PgVectorConfig`
- `PgVectorRagVectorStoreServiceImpl`
- `RagServiceImpl`
- `docs/pgvector-rag-migration.md`
- `deploy/docker-compose-pgvector.yml`
- `deploy/pgvector/init/01-init-pgvector.sql`

---

## 3. 每个改动有什么实际作用

### 3.1 `bootstrap-local.yml`

解决的问题：

1. 默认 `bootstrap.yml` 里写的是远程环境地址
2. 本地直接启动时会先去连 `192.168.100.232`
3. 本地 `Nacos` 账号环境和默认配置不一致时，会直接启动失败

现在加了 `local` profile 后，本地验证 `pgvector` 版 RAG 时可以直接绕过这些非核心阻塞。

### 3.2 `PgVectorRagVectorStoreServiceImpl`

联调里确认了这部分在启动阶段能做的事情：

- 自动执行 `create extension if not exists vector`
- 自动建表 `rag_vector_chunks`
- 自动创建 `file_id` 普通索引
- 自动创建 `embedding` 的 `hnsw` 向量索引

这意味着 `pgvector` 已经不是“只有代码改了”，而是本地真实环境可初始化。

### 3.3 `RagServiceImpl`

这次联调也顺手确认了一处真实编译问题已经修掉：

- 原先还残留 `countIndexedChunks(fileId)` 老调用
- 现在已经改成 `ragVectorStoreService.countByFileId(fileId)`

作用：

- 保证 `pgvector` 索引复用逻辑能正常编译通过

---

## 4. 这次本地联调实际验证了什么

### 4.1 Maven 已可用

本机已经安装并验证：

- `Apache Maven 3.9.9`

安装目录：

- `D:\Proejct_improvement\tools\apache-maven-3.9.9`

### 4.2 pgvector 容器已启动

已验证：

- `bite_excel-pgvector` 容器运行正常
- PostgreSQL 数据库 `chat2excel_vector` 可访问
- `vector` 扩展已启用

### 4.3 向量表和索引已存在

已验证 `rag_vector_chunks` 存在，并且已经有这些索引：

- `rag_vector_chunks_pkey`
- `idx_rag_vector_chunks_file_id`
- `idx_rag_vector_chunks_embedding_hnsw`

说明：

- `pgvector` 初始化逻辑和索引建表逻辑可用

### 4.4 编译通过

已执行通过：

```bash
mvn -pl ai-service -am -DskipTests compile
```

说明：

- `ai-service` 当前 `pgvector` 版代码至少在编译层面是通的

### 4.5 `ai-service` 已本地启动成功

这次已经验证：

- 在关闭 `Nacos` 和网关校验后
- `ai-service` 能成功启动在 `9002` 端口
- `PostgreSQL(pgvector)` 数据源能成功初始化

说明：

- `pgvector` 版改造本身没有把服务启动链路搞坏

---

## 5. 联调过程中发现的真实问题

### 5.1 本地 `Nacos` 认证失败

真实报错是：

- `403 user not found!`

这说明当前本地 `Nacos` 环境和项目默认配置不一致。

结论：

- 当前阻塞不在 `pgvector`
- 而在本地注册中心账号环境

所以这次才补了 `bootstrap-local.yml`，先把 `ai-service + MySQL + Redis + pgvector` 主链路跑通。

### 5.2 目前还没有做“真实问答写入向量”的接口级验证

当前已验证到：

- 容器正常
- 表正常
- 索引正常
- 服务正常启动

但还没完成的最后一步是：

- 走一次真实上传文件/提问流程
- 让系统真正调用 embedding
- 把 chunk 写入 `rag_vector_chunks`
- 再做第二次提问验证“索引复用”

这个阶段需要：

- 有可用的业务数据
- 有可访问的前端或接口调用链
- 有有效的用户登录态

---

## 6. 现在怎么启动最省事

### 6.1 启动 pgvector

```bash
docker compose -p bite_excel -f deploy/docker-compose-pgvector.yml up -d
```

### 6.2 用 local profile 启动 ai-service

```powershell
$env:MAVEN_HOME='D:\Proejct_improvement\tools\apache-maven-3.9.9'
$env:Path="$env:MAVEN_HOME\bin;$env:Path"
$env:DASHSCOPE_API_KEY='你的 DashScope Key'

mvn -pl ai-service -DskipTests spring-boot:run "-Dspring-boot.run.profiles=local"
```

说明：

- `DASHSCOPE_API_KEY` 只建议放环境变量
- 不建议写回仓库配置文件

---

## 7. 你下一步最值得做的验证

建议按这个顺序继续：

1. 上传一个销售报表文件
2. 第一次提问，观察是否触发建索引
3. 查询 `rag_vector_chunks`，确认 chunk 已入库
4. 第二次问同一文件问题，观察是否直接复用索引
5. 修改数据后再次提问，观察是否先删后重建

如果这 5 步都通了，就说明这次 `pgvector` 版 RAG 已经从“代码改造完成”进入“业务链路跑通”的状态。

---

## 8. 一句话结论

这次联调已经确认：**`pgvector` 版 RAG 的编译、建表、索引初始化、服务启动链路都已经跑通；当前剩余问题主要是本地 `Nacos` 环境和最终业务接口级写入验证。**
