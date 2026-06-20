# Chat2Excel OpenSpec 使用说明

这次把 OpenSpec 放进仓库，不是为了再造一个普通文档目录，而是把它作为后续需求变更的闸门。

以后涉及核心能力变化，尤其是 `ai-service` 的 RAG、SQL 生成、SSE 流程、缓存失效、MCP 工具等能力，建议先写 OpenSpec 变更，再动代码。

## 1. 当前初始化结果

本仓库已经在 git 根目录完成初始化：

```powershell
cd D:\Proejct_improvement\AI-form-assistant-remote
npx @fission-ai/openspec init . --tools codex
```

新增目录：

- `.codex/skills`
- `openspec/changes`
- `openspec/specs`

其中 `.codex/skills` 是 OpenSpec 为 Codex 生成的辅助技能；`openspec/changes` 用来放待实施的需求变更；`openspec/specs` 用来保存归档后的长期能力规格。

## 2. 第一条变更

当前第一条 OpenSpec 变更是：

```text
improve-sales-rag-observability
```

位置：

```text
openspec/changes/improve-sales-rag-observability
```

它的目标是把销售报表 RAG 的行为规范化，包括：

- pgvector 索引构建与复用
- 销售术语归一化和混合检索
- 多 Sheet 候选表排序
- RAG 上下文注入 SQL 生成流程
- SSE 阶段展示
- 修改数据后的向量索引失效

这条变更现在已经具备完整 OpenSpec 四件套：

- `proposal.md`
- `design.md`
- `specs/sales-rag/spec.md`
- `tasks.md`

## 3. 后续怎么用

查看当前变更：

```powershell
npx @fission-ai/openspec list
npx @fission-ai/openspec show improve-sales-rag-observability
```

校验全部 OpenSpec 内容：

```powershell
npx @fission-ai/openspec validate --all --strict
```

后续如果要继续实现这条变更，先看：

```text
openspec/changes/improve-sales-rag-observability/tasks.md
```

完成任务并验证通过后，再归档：

```powershell
npx @fission-ai/openspec archive improve-sales-rag-observability
```

归档后，`sales-rag` 的规格会进入长期规格目录，后续再改 RAG 时就基于长期规格做 delta。

## 4. 建议的变更命名方式

后续变更名用 kebab-case，建议写成动词开头：

- `add-rag-debug-panel`
- `improve-sales-time-parsing`
- `standardize-ai-sse-events`
- `support-chart-export`
- `harden-sql-generation-guardrails`

这样 OpenSpec 目录本身就能看出项目演进历史。

## 5. 什么时候不需要 OpenSpec

下面这些小改动可以直接改代码，不必强行写变更：

- 修 typo
- 调整注释
- 修改 README 中的非行为说明
- 小范围格式化
- 不影响接口、数据、AI 流程、用户行为的内部重命名

但如果变化会影响“用户能做什么”“系统必须怎么响应”“失败时怎么降级”“数据怎么变更”，就应该先走 OpenSpec。
