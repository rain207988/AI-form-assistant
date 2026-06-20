## Why

Sales-report RAG is now the highest-value AI capability in Chat2Excel, but its expected behavior is still spread across code comments and several narrative docs. We need a formal OpenSpec change so future RAG upgrades start from clear retrieval, ranking, SSE, and cache-invalidation acceptance criteria instead of re-discovering intent from implementation details.

## What Changes

- Define the sales-report RAG capability as a long-term spec-owned behavior.
- Standardize how retrieval context, candidate table ranking, SQL generation support, SSE progress, and fallback messages should behave.
- Make cache invalidation and pgvector reuse observable enough for debugging and user trust.
- Document the implementation tasks needed before this change can be archived into the main `sales-rag` spec.

## Capabilities

### New Capabilities

- `sales-rag`: Covers sales-report-focused file RAG, including pgvector indexing, semantic and lexical retrieval, table ranking, prompt context injection, SSE stage visibility, and stale-index prevention.

### Modified Capabilities

- None yet. This is the first OpenSpec capability for the existing sales-report RAG behavior.

## Impact

- Affected backend area: `ai-service`, especially `RagServiceImpl`, `PgVectorRagVectorStoreServiceImpl`, `AiServiceImpl`, `AiModelServiceImpl`, `RagContext`, and `ProcessStage`.
- Affected configuration: `chat2excel.rag.*`, `chat2excel.pgvector.*`, and local validation through `ai-service/src/main/resources/bootstrap-local.yml`.
- Affected docs/specs: new OpenSpec artifacts under `openspec/changes/improve-sales-rag-observability` and future long-term spec under `openspec/specs/sales-rag`.
- No breaking API changes are planned for this proposal.
