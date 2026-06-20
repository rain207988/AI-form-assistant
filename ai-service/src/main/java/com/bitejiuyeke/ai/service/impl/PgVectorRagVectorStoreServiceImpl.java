package com.bitejiuyeke.ai.service.impl;

import com.bitejiuyeke.ai.config.PgVectorProperties;
import com.bitejiuyeke.ai.dto.rag.RagVectorChunk;
import com.bitejiuyeke.ai.service.RagVectorStoreService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * 基于 PostgreSQL pgvector 的向量存储实现
 */
@Service
@Slf4j
public class PgVectorRagVectorStoreServiceImpl implements RagVectorStoreService {

    private final JdbcTemplate pgVectorJdbcTemplate;
    private final PgVectorProperties pgVectorProperties;

    public PgVectorRagVectorStoreServiceImpl(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
            PgVectorProperties pgVectorProperties
    ) {
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.pgVectorProperties = pgVectorProperties;
    }

    @PostConstruct
    @Override
    public void ensureSchema() {
        if (!pgVectorProperties.isEnabled()) {
            return;
        }
        try {
            pgVectorJdbcTemplate.execute("create extension if not exists vector");
            pgVectorJdbcTemplate.execute("""
                    create table if not exists %s (
                        chunk_id varchar(128) primary key,
                        file_id bigint not null,
                        table_name varchar(128),
                        sheet_name varchar(128),
                        chunk_type varchar(64) not null,
                        content text not null,
                        keywords text,
                        canonical_terms text,
                        business_summary text,
                        time_summary text,
                        embedding vector(%s),
                        created_at timestamp default current_timestamp
                    )
                    """.formatted(pgVectorProperties.getTableName(), pgVectorProperties.getDimensions()));
            pgVectorJdbcTemplate.execute("""
                    create index if not exists idx_%s_file_id
                    on %s (file_id)
                    """.formatted(pgVectorProperties.getTableName(), pgVectorProperties.getTableName()));
            pgVectorJdbcTemplate.execute("""
                    create index if not exists idx_%s_embedding_hnsw
                    on %s using hnsw (embedding vector_cosine_ops)
                    """.formatted(pgVectorProperties.getTableName(), pgVectorProperties.getTableName()));
            log.info("pgvector结构初始化完成, tableName={}, dimensions={}",
                    pgVectorProperties.getTableName(), pgVectorProperties.getDimensions());
        } catch (Exception e) {
            log.warn("初始化 pgvector 结构失败: {}", e.getMessage());
        }
    }

    @Override
    public void deleteByFileId(Long fileId) {
        if (!pgVectorProperties.isEnabled() || fileId == null) {
            return;
        }
        int deletedCount = pgVectorJdbcTemplate.update(
                "delete from " + pgVectorProperties.getTableName() + " where file_id = ?",
                fileId
        );
        log.info("pgvector文件索引删除完成, fileId={}, deletedChunkCount={}", fileId, deletedCount);
    }

    @Override
    public void saveChunks(List<RagVectorChunk> chunks) {
        if (!pgVectorProperties.isEnabled() || CollectionUtils.isEmpty(chunks)) {
            return;
        }
        String sql = """
                insert into %s (
                    chunk_id, file_id, table_name, sheet_name, chunk_type,
                    content, keywords, canonical_terms, business_summary, time_summary, embedding
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector)
                on conflict (chunk_id) do update set
                    file_id = excluded.file_id,
                    table_name = excluded.table_name,
                    sheet_name = excluded.sheet_name,
                    chunk_type = excluded.chunk_type,
                    content = excluded.content,
                    keywords = excluded.keywords,
                    canonical_terms = excluded.canonical_terms,
                    business_summary = excluded.business_summary,
                    time_summary = excluded.time_summary,
                    embedding = excluded.embedding
                """.formatted(pgVectorProperties.getTableName());

        pgVectorJdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                RagVectorChunk chunk = chunks.get(i);
                ps.setString(1, chunk.getChunkId());
                ps.setLong(2, chunk.getFileId());
                ps.setString(3, chunk.getTableName());
                ps.setString(4, chunk.getSheetName());
                ps.setString(5, chunk.getChunkType());
                ps.setString(6, chunk.getContent());
                ps.setString(7, chunk.getKeywords());
                ps.setString(8, chunk.getCanonicalTerms());
                ps.setString(9, chunk.getBusinessSummary());
                ps.setString(10, chunk.getTimeSummary());
                ps.setString(11, toPgVectorLiteral(chunk.getEmbedding()));
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
        Long fileId = chunks.get(0).getFileId();
        log.info("pgvector向量chunk写入完成, fileId={}, chunkCount={}", fileId, chunks.size());
    }

    @Override
    public boolean existsByFileId(Long fileId) {
        if (!pgVectorProperties.isEnabled() || fileId == null) {
            return false;
        }
        return countByFileId(fileId) > 0;
    }

    @Override
    public int countByFileId(Long fileId) {
        if (!pgVectorProperties.isEnabled() || fileId == null) {
            return 0;
        }
        Long count = pgVectorJdbcTemplate.queryForObject(
                "select count(1) from " + pgVectorProperties.getTableName() + " where file_id = ?",
                Long.class,
                fileId
        );
        return count == null ? 0 : count.intValue();
    }

    @Override
    public List<RagVectorChunk> similaritySearch(Long fileId, float[] queryEmbedding, int limit) {
        if (!pgVectorProperties.isEnabled() || fileId == null || queryEmbedding == null || queryEmbedding.length == 0) {
            return Collections.emptyList();
        }
        String sql = """
                select
                    chunk_id,
                    file_id,
                    table_name,
                    sheet_name,
                    chunk_type,
                    content,
                    keywords,
                    canonical_terms,
                    business_summary,
                    time_summary
                from %s
                where file_id = ?
                order by embedding <=> ?::vector
                limit ?
                """.formatted(pgVectorProperties.getTableName());

        List<RagVectorChunk> chunks = pgVectorJdbcTemplate.query(sql, ragVectorChunkRowMapper(), fileId, toPgVectorLiteral(queryEmbedding), limit);
        log.info("pgvector相似度检索完成, fileId={}, candidateLimit={}, candidateCount={}",
                fileId, limit, chunks.size());
        return chunks;
    }

    private RowMapper<RagVectorChunk> ragVectorChunkRowMapper() {
        return (rs, rowNum) -> RagVectorChunk.builder()
                .chunkId(rs.getString("chunk_id"))
                .fileId(rs.getLong("file_id"))
                .tableName(rs.getString("table_name"))
                .sheetName(rs.getString("sheet_name"))
                .chunkType(rs.getString("chunk_type"))
                .content(rs.getString("content"))
                .keywords(rs.getString("keywords"))
                .canonicalTerms(rs.getString("canonical_terms"))
                .businessSummary(rs.getString("business_summary"))
                .timeSummary(rs.getString("time_summary"))
                .build();
    }

    private String toPgVectorLiteral(float[] values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(",");
            }
            builder.append(values[i]);
        }
        builder.append("]");
        return builder.toString();
    }
}
