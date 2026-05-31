package com.bitejiuyeke.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitejiuyeke.ai.config.RagProperties;
import com.bitejiuyeke.ai.dto.rag.RagContext;
import com.bitejiuyeke.ai.mapper.FieldMappingsReadOnlyMapper;
import com.bitejiuyeke.ai.mapper.FileTableMappingsReadOnlyMapper;
import com.bitejiuyeke.ai.service.FileMetaDataService;
import com.bitejiuyeke.ai.service.RagService;
import com.bitejiuyeke.file.entity.FieldMappingEntity;
import com.bitejiuyeke.file.entity.FileTableMappingEntity;
import com.bitejiuyeke.file.entity.FilesEntity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 基于当前Excel元数据和样例数据的轻量RAG实现
 */
@Service
@Slf4j
public class RagServiceImpl implements RagService {

    private static final String CHUNK_TYPE_FILE = "file_summary";
    private static final String CHUNK_TYPE_TABLE = "table_schema";
    private static final String CHUNK_TYPE_SAMPLE = "table_samples";

    private final ConcurrentHashMap<Long, FileKnowledgeIndex> indexCache = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private FileMetaDataService fileMetaDataService;

    @Autowired
    private FileTableMappingsReadOnlyMapper fileTableMappingsReadOnlyMapper;

    @Autowired
    private FieldMappingsReadOnlyMapper fieldMappingsReadOnlyMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public RagContext retrieveContext(Long fileId, String userInput) {
        if (!ragProperties.isEnabled()) {
            return RagContext.empty("RAG未启用，继续走原始AI流程");
        }
        if (embeddingModel == null) {
            return RagContext.empty("当前未检测到EmbeddingModel，RAG自动降级");
        }
        if (!StringUtils.hasText(userInput)) {
            return RagContext.empty("用户问题为空，跳过RAG检索");
        }

        try {
            IndexResolution resolution = resolveIndex(fileId);
            FileKnowledgeIndex knowledgeIndex = resolution.getKnowledgeIndex();
            if (CollectionUtils.isEmpty(knowledgeIndex.getChunks())) {
                return RagContext.empty("当前文件没有可检索的知识片段，跳过RAG");
            }

            float[] queryEmbedding = embeddingModel.embed(userInput);
            List<ScoredChunk> matchedChunks = knowledgeIndex.getChunks()
                    .stream()
                    .map(chunk -> scoreChunk(chunk, queryEmbedding))
                    .filter(scoredChunk -> scoredChunk.getScore() >= ragProperties.getSimilarityThreshold())
                    .sorted(Comparator.comparingDouble(ScoredChunk::getScore).reversed())
                    .limit(ragProperties.getTopK())
                    .toList();

            if (matchedChunks.isEmpty()) {
                return RagContext.builder()
                        .enabled(true)
                        .cacheHit(resolution.isCacheHit())
                        .indexedChunkCount(knowledgeIndex.getChunks().size())
                        .matchedChunkCount(0)
                        .matchedTableNames(Collections.emptyList())
                        .retrieveSummary("RAG未命中高相关片段，回退到原始Prompt")
                        .promptContext("")
                        .build();
            }

            List<String> matchedTableNames = matchedChunks.stream()
                    .map(scoredChunk -> scoredChunk.getChunk().getTableName())
                    .filter(StringUtils::hasText)
                    .distinct()
                    .collect(Collectors.toList());

            String promptContext = buildPromptContext(matchedChunks);
            String retrieveSummary = buildRetrieveSummary(matchedChunks, matchedTableNames, resolution.isCacheHit());

            return RagContext.builder()
                    .enabled(true)
                    .cacheHit(resolution.isCacheHit())
                    .indexedChunkCount(knowledgeIndex.getChunks().size())
                    .matchedChunkCount(matchedChunks.size())
                    .matchedTableNames(matchedTableNames)
                    .retrieveSummary(retrieveSummary)
                    .promptContext(promptContext)
                    .build();
        } catch (Exception e) {
            log.error("RAG检索失败, fileId={}, message={}", fileId, e.getMessage());
            log.error("RAG检索异常明细", e);
            return RagContext.empty("RAG检索失败，自动回退到原始AI流程");
        }
    }

    private IndexResolution resolveIndex(Long fileId) {
        FileKnowledgeIndex cachedIndex = indexCache.get(fileId);
        if (cachedIndex != null && !isExpired(cachedIndex)) {
            return new IndexResolution(true, cachedIndex);
        }

        synchronized (this) {
            FileKnowledgeIndex secondCheck = indexCache.get(fileId);
            if (secondCheck != null && !isExpired(secondCheck)) {
                return new IndexResolution(true, secondCheck);
            }

            FileKnowledgeIndex builtIndex = buildIndex(fileId);
            indexCache.put(fileId, builtIndex);
            return new IndexResolution(false, builtIndex);
        }
    }

    private boolean isExpired(FileKnowledgeIndex knowledgeIndex) {
        long expireAt = knowledgeIndex.getCreatedAtEpochMilli() + ragProperties.getCacheMinutes() * 60_000L;
        return System.currentTimeMillis() > expireAt;
    }

    private FileKnowledgeIndex buildIndex(Long fileId) {
        FilesEntity file = fileMetaDataService.getFileById(fileId);
        List<FileTableMappingEntity> tableMappings = listTableMappings(fileId);
        List<KnowledgeChunk> chunks = new ArrayList<>();

        if (file != null) {
            chunks.add(buildFileSummaryChunk(file, tableMappings));
        }

        for (FileTableMappingEntity tableMapping : tableMappings) {
            List<FieldMappingEntity> fieldMappings = listFieldMappings(fileId, tableMapping.getTableName());
            chunks.add(buildTableSummaryChunk(file, tableMapping, fieldMappings));
            chunks.addAll(buildSampleChunks(tableMapping, fieldMappings));
        }

        chunks.removeIf(chunk -> !StringUtils.hasText(chunk.getContent()));
        embedChunks(chunks);

        return new FileKnowledgeIndex(Instant.now().toEpochMilli(), chunks);
    }

    private KnowledgeChunk buildFileSummaryChunk(FilesEntity file, List<FileTableMappingEntity> tableMappings) {
        StringBuilder content = new StringBuilder();
        content.append("文件名：").append(defaultValue(file.getFileName(), "未知文件")).append("\n");
        content.append("文件ID：").append(file.getId()).append("\n");
        content.append("Sheet数量：").append(tableMappings.size()).append("\n");
        if (!tableMappings.isEmpty()) {
            String tableSummary = tableMappings.stream()
                    .map(mapping -> String.format(Locale.ROOT, "sheet=%s(table=%s)",
                            defaultValue(mapping.getSheetName(), "未命名Sheet"),
                            defaultValue(mapping.getTableName(), "未知表")))
                    .collect(Collectors.joining(", "));
            content.append("Sheet与表映射：").append(tableSummary).append("\n");
        }

        return new KnowledgeChunk(
                "file-" + file.getId(),
                null,
                CHUNK_TYPE_FILE,
                content.toString().trim(),
                null
        );
    }

    private KnowledgeChunk buildTableSummaryChunk(
            FilesEntity file,
            FileTableMappingEntity tableMapping,
            List<FieldMappingEntity> fieldMappings
    ) {
        String tableName = tableMapping.getTableName();
        long rowCount = countRows(tableName);
        String originalHeaders = fieldMappings.stream()
                .map(FieldMappingEntity::getOriginalHeader)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(", "));
        String fieldMappingSummary = fieldMappings.stream()
                .map(item -> defaultValue(item.getDbFieldName(), "unknown") + "->" + defaultValue(item.getOriginalHeader(), "unknown"))
                .collect(Collectors.joining(", "));

        StringBuilder content = new StringBuilder();
        content.append("文件名：").append(file == null ? "未知文件" : defaultValue(file.getFileName(), "未知文件")).append("\n");
        content.append("Sheet名称：").append(defaultValue(tableMapping.getSheetName(), "未命名Sheet")).append("\n");
        content.append("数据库表：").append(defaultValue(tableName, "未知表")).append("\n");
        content.append("总行数：").append(rowCount).append("\n");
        content.append("原始表头：").append(defaultValue(originalHeaders, "无表头信息")).append("\n");
        content.append("字段映射：").append(defaultValue(fieldMappingSummary, "无字段映射")).append("\n");

        return new KnowledgeChunk(
                "table-" + tableName,
                tableName,
                CHUNK_TYPE_TABLE,
                content.toString().trim(),
                null
        );
    }

    private List<KnowledgeChunk> buildSampleChunks(FileTableMappingEntity tableMapping, List<FieldMappingEntity> fieldMappings) {
        List<Map<String, Object>> sampleRows = listSampleRows(tableMapping.getTableName());
        if (sampleRows.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> orderedHeaders = fieldMappings.stream()
                .map(FieldMappingEntity::getOriginalHeader)
                .filter(StringUtils::hasText)
                .toList();

        List<KnowledgeChunk> chunks = new ArrayList<>();
        int rowsPerChunk = Math.max(1, ragProperties.getRowsPerChunk());
        int chunkIndex = 0;
        for (int i = 0; i < sampleRows.size(); i += rowsPerChunk) {
            int end = Math.min(i + rowsPerChunk, sampleRows.size());
            List<Map<String, Object>> subRows = sampleRows.subList(i, end);
            StringBuilder content = new StringBuilder();
            content.append("数据库表：").append(defaultValue(tableMapping.getTableName(), "未知表")).append("\n");
            content.append("Sheet名称：").append(defaultValue(tableMapping.getSheetName(), "未命名Sheet")).append("\n");
            if (!orderedHeaders.isEmpty()) {
                content.append("相关表头：").append(String.join(", ", orderedHeaders)).append("\n");
            }
            content.append("样例数据片段：").append(chunkIndex + 1).append("\n");
            for (int rowIndex = 0; rowIndex < subRows.size(); rowIndex++) {
                content.append("第").append(i + rowIndex + 1).append("行：")
                        .append(stringifyRow(subRows.get(rowIndex)))
                        .append("\n");
            }

            chunks.add(new KnowledgeChunk(
                    "sample-" + tableMapping.getTableName() + "-" + chunkIndex,
                    tableMapping.getTableName(),
                    CHUNK_TYPE_SAMPLE,
                    content.toString().trim(),
                    null
            ));
            chunkIndex++;
        }
        return chunks;
    }

    private void embedChunks(List<KnowledgeChunk> chunks) {
        if (CollectionUtils.isEmpty(chunks)) {
            return;
        }
        List<String> texts = chunks.stream()
                .map(KnowledgeChunk::getContent)
                .toList();
        List<float[]> embeddings = embeddingModel.embed(texts);
        if (embeddings == null || embeddings.size() != chunks.size()) {
            throw new IllegalStateException("知识片段向量化失败，返回结果数量异常");
        }

        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setEmbedding(embeddings.get(i));
        }
    }

    private ScoredChunk scoreChunk(KnowledgeChunk chunk, float[] queryEmbedding) {
        return new ScoredChunk(chunk, cosineSimilarity(queryEmbedding, chunk.getEmbedding()));
    }

    private double cosineSimilarity(float[] queryVector, float[] documentVector) {
        if (queryVector == null || documentVector == null || queryVector.length == 0 || documentVector.length == 0) {
            return 0D;
        }
        int length = Math.min(queryVector.length, documentVector.length);
        double dotProduct = 0D;
        double queryNorm = 0D;
        double documentNorm = 0D;

        for (int i = 0; i < length; i++) {
            dotProduct += queryVector[i] * documentVector[i];
            queryNorm += queryVector[i] * queryVector[i];
            documentNorm += documentVector[i] * documentVector[i];
        }

        if (queryNorm == 0D || documentNorm == 0D) {
            return 0D;
        }
        return dotProduct / (Math.sqrt(queryNorm) * Math.sqrt(documentNorm));
    }

    private String buildPromptContext(List<ScoredChunk> matchedChunks) {
        StringBuilder promptContext = new StringBuilder();
        promptContext.append("以下内容来自当前Excel文件的RAG检索结果，请优先依据这些事实理解用户问题、表名和字段含义：\n");
        int index = 1;
        for (ScoredChunk matchedChunk : matchedChunks) {
            promptContext.append("片段").append(index++)
                    .append(" [表=").append(defaultValue(matchedChunk.getChunk().getTableName(), "文件级"))
                    .append(", 类型=").append(matchedChunk.getChunk().getChunkType())
                    .append(", 相似度=").append(String.format(Locale.ROOT, "%.4f", matchedChunk.getScore()))
                    .append("]\n")
                    .append(matchedChunk.getChunk().getContent())
                    .append("\n");
        }
        return promptContext.toString().trim();
    }

    private String buildRetrieveSummary(List<ScoredChunk> matchedChunks, List<String> matchedTableNames, boolean cacheHit) {
        String tableText = matchedTableNames.isEmpty() ? "未定位到具体表" : String.join(", ", matchedTableNames);
        return String.format(Locale.ROOT,
                "%sRAG索引，命中%d个片段，关联表：%s",
                cacheHit ? "复用" : "构建",
                matchedChunks.size(),
                tableText
        );
    }

    private List<FileTableMappingEntity> listTableMappings(Long fileId) {
        LambdaQueryWrapper<FileTableMappingEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FileTableMappingEntity::getFileId, fileId)
                .orderByAsc(FileTableMappingEntity::getSheetIndex)
                .orderByAsc(FileTableMappingEntity::getId);
        return fileTableMappingsReadOnlyMapper.selectList(queryWrapper);
    }

    private List<FieldMappingEntity> listFieldMappings(Long fileId, String tableName) {
        LambdaQueryWrapper<FieldMappingEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FieldMappingEntity::getFileId, fileId)
                .eq(FieldMappingEntity::getTableName, tableName)
                .orderByAsc(FieldMappingEntity::getFieldOrder)
                .orderByAsc(FieldMappingEntity::getId);
        return fieldMappingsReadOnlyMapper.selectList(queryWrapper);
    }

    private long countRows(String tableName) {
        try {
            Long count = jdbcTemplate.queryForObject("select count(*) from `" + tableName + "`", Long.class);
            return count == null ? 0L : count;
        } catch (Exception e) {
            log.warn("统计表行数失败, tableName={}, message={}", tableName, e.getMessage());
            return 0L;
        }
    }

    private List<Map<String, Object>> listSampleRows(String tableName) {
        try {
            String sql = "select * from `" + tableName + "` limit " + Math.max(1, ragProperties.getSampleRowsPerTable());
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            return fileMetaDataService.mapQuery(rows, tableName);
        } catch (Exception e) {
            log.warn("查询样例数据失败, tableName={}, message={}", tableName, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String stringifyRow(Map<String, Object> row) {
        return row.entrySet()
                .stream()
                .map(entry -> entry.getKey() + "=" + shortenValue(entry.getValue()))
                .collect(Collectors.joining("；"));
    }

    private String shortenValue(Object value) {
        String text = Objects.toString(value, "");
        if (text.length() <= 80) {
            return text;
        }
        return text.substring(0, 77) + "...";
    }

    private String defaultValue(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    @Getter
    @AllArgsConstructor
    private static class IndexResolution {
        private final boolean cacheHit;
        private final FileKnowledgeIndex knowledgeIndex;
    }

    @Getter
    @AllArgsConstructor
    private static class FileKnowledgeIndex {
        private final long createdAtEpochMilli;
        private final List<KnowledgeChunk> chunks;
    }

    @Getter
    @AllArgsConstructor
    private static class ScoredChunk {
        private final KnowledgeChunk chunk;
        private final double score;
    }

    @Getter
    private static class KnowledgeChunk {
        private final String id;
        private final String tableName;
        private final String chunkType;
        private final String content;
        private float[] embedding;

        private KnowledgeChunk(String id, String tableName, String chunkType, String content, float[] embedding) {
            this.id = id;
            this.tableName = tableName;
            this.chunkType = chunkType;
            this.content = content;
            this.embedding = embedding;
        }

        private void setEmbedding(float[] embedding) {
            this.embedding = embedding;
        }
    }
}
