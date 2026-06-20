## ADDED Requirements

### Requirement: Pgvector File Index Lifecycle
The system SHALL build, persist, reuse, and invalidate a file-scoped pgvector RAG index for sales-report AI requests.

#### Scenario: Build missing file index
- **WHEN** RAG is enabled, pgvector is enabled, an embedding model is available, and no vector chunks exist for the requested file
- **THEN** the system MUST build chunks from file metadata, sheet/table mappings, field mappings, representative row samples, sales synonym metadata, and time/business summaries
- **AND** the system MUST persist those chunks to the configured pgvector table

#### Scenario: Reuse existing file index
- **WHEN** vector chunks already exist for the requested file
- **THEN** the system MUST reuse the existing pgvector index instead of rebuilding all chunks
- **AND** the returned RAG context MUST indicate that the index was reused

#### Scenario: Graceful RAG fallback
- **WHEN** RAG is disabled, pgvector is disabled, the embedding model is unavailable, the file ID is missing, or the user question is empty
- **THEN** the system MUST return an empty RAG context with a human-readable fallback summary
- **AND** the AI request MUST continue through the non-RAG flow instead of failing solely because RAG is unavailable

### Requirement: Sales-Aware Retrieval Context
The system SHALL retrieve and assemble sales-report-aware RAG context using both semantic similarity and business-term matching.

#### Scenario: Expand sales synonyms
- **WHEN** the user asks a sales-report question containing terms such as sales amount, GMV, revenue, quantity, gross profit, region, channel, customer, product, salesperson, month, or quarter
- **THEN** the system MUST normalize matching configured sales synonym groups before retrieval and ranking

#### Scenario: Retrieve relevant chunks
- **WHEN** pgvector returns candidate chunks for the requested file
- **THEN** the system MUST rerank candidates using vector similarity, lexical term matches, canonical sales-term matches, table context, and time/business summaries
- **AND** the final RAG context MUST include only the top relevant chunks needed for downstream prompt context

#### Scenario: Low relevance fallback
- **WHEN** pgvector returns no usable candidates or all candidates are below the effective relevance threshold
- **THEN** the system MUST return a RAG context that explains the low-relevance fallback
- **AND** downstream SQL generation MUST continue without relying on empty RAG facts

### Requirement: Candidate Table Ranking
The system SHALL produce table-level ranking information for multi-sheet sales files.

#### Scenario: Multi-table sales query
- **WHEN** a file contains multiple sheets or dynamic tables and the user asks a sales-report question
- **THEN** the system MUST aggregate scored chunks into table-level candidates
- **AND** the system MUST expose ranked table summaries in the RAG context

#### Scenario: Prefer RAG-ranked table
- **WHEN** the RAG context identifies one or more relevant table names
- **THEN** the AI table-selection flow MUST be able to prefer the highest-ranked RAG table before falling back to generic field-mapping heuristics

### Requirement: Prompt Context Injection
The system SHALL inject trustworthy RAG context into AI intent detection, table selection, SQL generation, and final response synthesis.

#### Scenario: Query SQL generation with RAG facts
- **WHEN** RAG context is enabled and contains matched table names, field mappings, sample rows, business summaries, or time summaries
- **THEN** the SQL generation prompt MUST prioritize those current-file facts over model guesses
- **AND** generated SQL MUST target dynamic tables and mapped fields that exist in the current file context

#### Scenario: Natural language response with sales language
- **WHEN** SQL execution returns results for a sales-report request
- **THEN** the final AI response MUST summarize results using sales-report language such as sales amount, quantity, gross profit, region, customer, product, and time period when those concepts are present

### Requirement: SSE RAG Observability
The system SHALL expose RAG progress and retrieval summaries through the existing streaming response.

#### Scenario: Emit RAG index stage
- **WHEN** `/ai/chat/stream` processes a request that reaches RAG preparation
- **THEN** the stream MUST emit a `BUILD_RAG_INDEX` progress event
- **AND** the event data MUST indicate whether the index was built or reused and how many chunks are available

#### Scenario: Emit RAG retrieval stage
- **WHEN** RAG retrieval completes, falls back, or fails safely
- **THEN** the stream MUST emit a `RETRIEVE_RAG_CONTEXT` progress event
- **AND** the event data MUST include matched chunk count, matched table names, ranked table summaries, or the fallback reason without exposing full raw prompt context

### Requirement: Stale Index Prevention
The system SHALL prevent modified sales data from being answered with stale RAG context.

#### Scenario: Invalidate after data mutation
- **WHEN** an AI update flow modifies data for a file
- **THEN** the system MUST invalidate the file's pgvector RAG index
- **AND** the next RAG-enabled question for that file MUST rebuild the vector chunks before retrieval

#### Scenario: Delete file vector chunks
- **WHEN** a file is removed, restored in a way that changes dynamic table content, or explicitly re-indexed
- **THEN** the system MUST be able to delete all pgvector chunks associated with that file ID
