## 1. Baseline Alignment

- [ ] 1.1 Compare `RagServiceImpl.retrieveContext(...)` with the `sales-rag` OpenSpec requirements and record any behavior gaps.
- [ ] 1.2 Verify `RagContext` carries index reuse, indexed chunk count, matched chunk count, matched table names, ranked table summaries, fallback summaries, and prompt context.
- [ ] 1.3 Verify `AiServiceImpl.prepareRagContextAndSendProgress(...)` emits both `BUILD_RAG_INDEX` and `RETRIEVE_RAG_CONTEXT` for successful retrieval and graceful fallback paths.
- [ ] 1.4 Verify data update paths call `RagService.invalidate(fileId)` after mutation.

## 2. Observability Implementation

- [ ] 2.1 Standardize the `BUILD_RAG_INDEX` SSE data payload so it clearly reports built versus reused index state and available chunk count.
- [ ] 2.2 Standardize the `RETRIEVE_RAG_CONTEXT` SSE data payload so it reports matched chunk count, matched table names, ranked table summaries, or fallback reason.
- [ ] 2.3 Ensure SSE summaries do not expose full raw prompt context or unnecessary sample row contents.
- [ ] 2.4 Add structured logs for pgvector index build, reuse, retrieval fallback, and invalidation with `fileId` but without sensitive row data.

## 3. Regression Checks

- [ ] 3.1 Add or document a repeatable smoke check for first-query index build and second-query index reuse.
- [ ] 3.2 Add or document a multi-sheet sales fixture that verifies candidate table ranking for region, customer, product, salesperson, sales amount, quantity, gross profit, month, and quarter queries.
- [ ] 3.3 Add or document a fallback check for disabled RAG, disabled pgvector, missing embedding model, missing file ID, and low-relevance retrieval.
- [ ] 3.4 Add or document an update-flow check proving modified file data invalidates pgvector chunks and the next query rebuilds the index.

## 4. Documentation And Validation

- [ ] 4.1 Update existing RAG docs so pgvector is described as the current baseline and old in-memory-only language is clearly marked historical.
- [x] 4.2 Add a short project note explaining how this repo uses OpenSpec as the change gate for future RAG and AI pipeline work.
- [x] 4.3 Run `npx @fission-ai/openspec validate --all --strict`.
- [ ] 4.4 After implementation and validation are complete, archive `improve-sales-rag-observability` into the long-term `sales-rag` spec.
