## Context

Chat2Excel's AI pipeline already uses sales-report-focused RAG inside `ai-service`. The current implementation builds file-scoped chunks from file metadata, sheet/table mappings, field mappings, sampled rows, sales synonyms, and time/business summaries; stores embeddings in PostgreSQL pgvector; reranks candidates in Java; injects prompt context into AI SQL generation; and emits RAG-related progress through SSE.

The main gap is not the absence of RAG, but the absence of a durable behavioral contract. Requirements are currently distributed across implementation classes and docs such as `docs/sales-rag-optimization.md`, `docs/pgvector-rag-migration.md`, and `docs/pgvector-local-validation.md`. OpenSpec should become the gate for future changes to retrieval, ranking, cache invalidation, and debug visibility.

## Goals / Non-Goals

**Goals:**

- Define a first-class `sales-rag` OpenSpec capability for the existing sales-report RAG behavior.
- Make retrieval, candidate table ranking, fallback, SSE visibility, and cache invalidation testable.
- Preserve the current architecture: MySQL remains the source of structured Excel data; pgvector handles vector recall; Java service logic handles sales-aware reranking and prompt assembly.
- Provide a safe next-step task list for code and documentation alignment.

**Non-Goals:**

- Replace pgvector with Milvus, Redis Vector, or another vector database.
- Redesign the public `/ai/chat/stream` API contract.
- Add a frontend RAG debug panel in this change.
- Introduce tenant-level enterprise audit or permissions beyond the current file/user checks.

## Decisions

1. Model `sales-rag` as a new OpenSpec capability.

   Rationale: RAG is now a cross-cutting behavior that affects retrieval, table selection, SQL generation, SSE, and data freshness. Keeping it as a standalone capability makes future changes easier to review than burying it under generic AI-chat documentation.

   Alternative considered: create separate specs for `pgvector-indexing`, `rag-ranking`, and `sse-progress`. That would be more granular, but too heavy for the first adoption step.

2. Keep pgvector responsible for recall and Java responsible for sales-aware reranking.

   Rationale: pgvector is a good persistent similarity store, while the existing Java logic knows the project-specific meaning of sales synonyms, dimensions, measures, time fields, and sheet/table mappings.

   Alternative considered: push all ranking into SQL/vector distance only. That would simplify code but lose sales-domain weighting and table-level explainability.

3. Treat SSE summaries as the first observability surface.

   Rationale: `/ai/chat/stream` already emits `BUILD_RAG_INDEX` and `RETRIEVE_RAG_CONTEXT`. Standardizing those payloads gives users and future frontend panels immediate insight without introducing a new debug endpoint first.

   Alternative considered: add a dedicated debug API immediately. That can still come later, but the existing stream is the lowest-friction contract.

4. Capture fallback as successful degradation, not an error path.

   Rationale: Missing embeddings, disabled pgvector, empty file IDs, or low-relevance results should not break AI chat. They should return a clear fallback summary and continue through the original AI flow.

   Alternative considered: fail the request when RAG is unavailable. That would be stricter, but worse for normal user experience.

## Risks / Trade-offs

- [Risk] Existing docs still mention the old in-memory RAG behavior in some places. -> Mitigation: add a task to update docs so long-term specs, README, and RAG docs describe pgvector as the current baseline.
- [Risk] SSE observability can accidentally expose too much row content if summaries include raw chunks. -> Mitigation: keep stream summaries focused on index source, chunk counts, matched table names, and ranked-table summaries instead of full prompt context.
- [Risk] Ranking behavior may be hard to verify without stable fixtures. -> Mitigation: add regression fixtures for multi-sheet sales files with known synonyms, time fields, and region/customer/product dimensions.
- [Risk] Cache invalidation is easy to regress when update/export code paths evolve. -> Mitigation: specify mutation-triggered invalidation and add tests around update flows that call `RagService.invalidate(fileId)`.

## Migration Plan

1. Validate this OpenSpec change with `npx @fission-ai/openspec validate --all --strict`.
2. Align code and docs against the new `sales-rag` requirements.
3. Add focused tests or repeatable smoke checks for index build/reuse, fallback, ranking, SSE summaries, and invalidation.
4. After implementation tasks are complete, archive `improve-sales-rag-observability` so the `sales-rag` requirements become long-term specs.

Rollback is straightforward because this change introduces planning artifacts first. If a later implementation change regresses runtime behavior, revert the code change while keeping or revising the OpenSpec requirements.
