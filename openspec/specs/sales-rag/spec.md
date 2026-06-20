# sales-rag Specification

## Purpose

定义 Chat2Excel 面向销售报表的文件级 RAG 行为契约。该能力以 pgvector 作为持久化向量召回层，以 Java 服务承载销售术语归一化、候选表排序、Prompt 上下文注入、SSE 可观测性和数据修改后的索引失效，确保后续 RAG 演进有稳定验收标准。

## Requirements
### Requirement: pgvector 文件索引生命周期
系统 SHALL（必须）为销售报表 AI 请求构建、持久化、复用并失效文件级 pgvector RAG 索引。

#### Scenario: 构建缺失的文件索引
- **WHEN** RAG 已启用、pgvector 已启用、embedding 模型可用，并且目标文件还没有向量 chunk
- **THEN** 系统必须基于文件元数据、Sheet/表映射、字段映射、代表性样例行、销售同义词元数据、时间/业务摘要构建 chunk
- **AND** 系统必须把这些 chunk 持久化到配置的 pgvector 表中

#### Scenario: 复用已有文件索引
- **WHEN** 目标文件已经存在向量 chunk
- **THEN** 系统必须复用已有 pgvector 索引，而不是重新构建全部 chunk
- **AND** 返回的 RAG 上下文必须说明本次复用了索引

#### Scenario: RAG 平滑降级
- **WHEN** RAG 未启用、pgvector 未启用、embedding 模型不可用、fileId 缺失，或用户问题为空
- **THEN** 系统必须返回带有人类可读降级摘要的空 RAG 上下文
- **AND** AI 请求必须继续走非 RAG 流程，不能仅因为 RAG 不可用而失败

### Requirement: 销售语义感知的检索上下文
系统 SHALL（必须）结合语义相似度和业务词匹配，检索并组装面向销售报表的 RAG 上下文。

#### Scenario: 扩展销售同义词
- **WHEN** 用户提出销售报表问题，且问题中包含销售额、GMV、营收、销量、毛利、区域、渠道、客户、产品、销售员、月份或季度等词
- **THEN** 系统必须在检索和排序前，按配置的销售同义词组完成归一化

#### Scenario: 检索相关 chunk
- **WHEN** pgvector 为目标文件返回候选 chunk
- **THEN** 系统必须结合向量相似度、关键词匹配、归一化销售术语匹配、表上下文、时间/业务摘要对候选 chunk 重新排序
- **AND** 最终 RAG 上下文必须只包含下游 Prompt 所需的最高相关 chunk

#### Scenario: 低相关性降级
- **WHEN** pgvector 没有返回可用候选，或所有候选都低于有效相关性阈值
- **THEN** 系统必须返回解释低相关性降级原因的 RAG 上下文
- **AND** 下游 SQL 生成必须继续执行，但不能依赖空的 RAG 事实

### Requirement: 候选表排序
系统 SHALL（必须）为多 Sheet 销售文件产出表级候选排序信息。

#### Scenario: 多表销售查询
- **WHEN** 一个文件包含多个 Sheet 或动态表，并且用户提出销售报表问题
- **THEN** 系统必须把已评分 chunk 聚合成表级候选
- **AND** 系统必须在 RAG 上下文中暴露候选表排序摘要

#### Scenario: 优先使用 RAG 排序表
- **WHEN** RAG 上下文识别出一个或多个相关表名
- **THEN** AI 选表流程必须能优先选择 RAG 排名最高的表，再回退到通用字段映射启发式逻辑

### Requirement: Prompt 上下文注入
系统 SHALL（必须）把可信的 RAG 上下文注入 AI 意图判断、选表、SQL 生成和最终响应总结流程。

#### Scenario: 基于 RAG 事实生成查询 SQL
- **WHEN** RAG 上下文已启用，并且包含命中表名、字段映射、样例行、业务摘要或时间摘要
- **THEN** SQL 生成 Prompt 必须优先使用这些当前文件事实，而不是依赖模型猜测
- **AND** 生成的 SQL 必须指向当前文件上下文中真实存在的动态表和映射字段

#### Scenario: 使用销售语言生成自然语言回复
- **WHEN** 销售报表请求的 SQL 执行返回结果
- **THEN** 如果结果中存在销售额、销量、毛利、区域、客户、产品、时间周期等概念，最终 AI 回复必须使用销售报表语言进行总结

### Requirement: SSE RAG 可观测性
系统 SHALL（必须）通过现有流式响应暴露 RAG 进度和检索摘要。

#### Scenario: 输出 RAG 索引阶段
- **WHEN** `/ai/chat/stream` 处理请求并进入 RAG 准备阶段
- **THEN** 流式响应必须输出 `BUILD_RAG_INDEX` 进度事件
- **AND** 事件数据必须说明索引是新建还是复用，以及当前可用 chunk 数量

#### Scenario: 输出 RAG 检索阶段
- **WHEN** RAG 检索完成、发生降级，或安全失败
- **THEN** 流式响应必须输出 `RETRIEVE_RAG_CONTEXT` 进度事件
- **AND** 事件数据必须包含命中 chunk 数量、命中表名、候选表排序摘要或降级原因，并且不能暴露完整原始 Prompt 上下文

### Requirement: 防止旧索引污染回答
系统 SHALL（必须）防止已修改销售数据继续使用旧 RAG 上下文回答。

#### Scenario: 数据变更后失效索引
- **WHEN** AI 更新流程修改了某个文件的数据
- **THEN** 系统必须让该文件的 pgvector RAG 索引失效
- **AND** 该文件下一次启用 RAG 的问题必须先重建向量 chunk 再检索

#### Scenario: 删除文件向量 chunk
- **WHEN** 文件被删除、以会改变动态表内容的方式恢复，或被显式要求重新索引
- **THEN** 系统必须能够删除该 fileId 关联的所有 pgvector chunk
