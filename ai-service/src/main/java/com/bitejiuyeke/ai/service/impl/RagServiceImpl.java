package com.bitejiuyeke.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bitejiuyeke.ai.config.PgVectorProperties;
import com.bitejiuyeke.ai.config.RagProperties;
import com.bitejiuyeke.ai.dto.rag.RagContext;
import com.bitejiuyeke.ai.dto.rag.RagVectorChunk;
import com.bitejiuyeke.ai.mapper.FieldMappingsReadOnlyMapper;
import com.bitejiuyeke.ai.mapper.FileTableMappingsReadOnlyMapper;
import com.bitejiuyeke.ai.service.FileMetaDataService;
import com.bitejiuyeke.ai.service.RagService;
import com.bitejiuyeke.ai.service.RagVectorStoreService;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 基于 pgvector 的销售报表增强版 RAG 实现
 */
@Service
@Slf4j
public class RagServiceImpl implements RagService {

    private static final String CHUNK_TYPE_FILE = "file_summary";
    private static final String CHUNK_TYPE_TABLE = "table_schema";
    private static final String CHUNK_TYPE_SAMPLE = "table_samples";
    private static final Pattern BUSINESS_TERM_PATTERN = Pattern.compile("[\\p{IsHan}A-Za-z0-9_-]{2,}");
    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd")
    );

    private final ConcurrentHashMap<Long, Object> buildLocks = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private PgVectorProperties pgVectorProperties;

    @Autowired
    private RagVectorStoreService ragVectorStoreService;

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
        if (!pgVectorProperties.isEnabled()) {
            return RagContext.empty("pgvector未启用，当前RAG自动降级");
        }
        if (embeddingModel == null) {
            return RagContext.empty("当前未检测到EmbeddingModel，RAG自动降级");
        }
        if (fileId == null) {
            return RagContext.empty("文件ID为空，跳过RAG检索");
        }
        if (!StringUtils.hasText(userInput)) {
            return RagContext.empty("用户问题为空，跳过RAG检索");
        }

        try {
            IndexResolution resolution = ensureVectorIndex(fileId);
            List<SynonymGroup> synonymGroups = buildSynonymGroups();
            QueryAnalysis queryAnalysis = analyzeQuery(userInput, synonymGroups);
            float[] queryEmbedding = embeddingModel.embed(queryAnalysis.getExpandedQuery());
            List<RagVectorChunk> candidates = ragVectorStoreService.similaritySearch(
                    fileId,
                    queryEmbedding,
                    Math.max(pgVectorProperties.getCandidateLimit(), ragProperties.getTopK() * 3)
            );

            if (CollectionUtils.isEmpty(candidates)) {
                return RagContext.builder()
                        .enabled(true)
                        .cacheHit(resolution.isCacheHit())
                        .indexedChunkCount(resolution.getIndexedChunkCount())
                        .matchedChunkCount(0)
                        .matchedTableNames(Collections.emptyList())
                        .rankedTables(Collections.emptyList())
                        .retrieveSummary("pgvector未命中可用片段，回退到原始Prompt")
                        .promptContext("")
                        .build();
            }

            List<ScoredChunk> scoredChunks = candidates.stream()
                    .map(chunk -> scoreChunk(chunk, queryAnalysis))
                    .sorted(Comparator.comparingDouble(ScoredChunk::getFinalScore).reversed())
                    .toList();

            TableSelection tableSelection = selectTables(scoredChunks, queryAnalysis);
            List<ScoredChunk> matchedChunks = collectTopChunks(tableSelection, scoredChunks);
            if (matchedChunks.isEmpty()) {
                return RagContext.builder()
                        .enabled(true)
                        .cacheHit(resolution.isCacheHit())
                        .indexedChunkCount(resolution.getIndexedChunkCount())
                        .matchedChunkCount(0)
                        .matchedTableNames(Collections.emptyList())
                        .rankedTables(Collections.emptyList())
                        .retrieveSummary("pgvector未命中高相关片段，回退到原始Prompt")
                        .promptContext("")
                        .build();
            }

            List<String> matchedTableNames = tableSelection.getMatchedTableNames();
            List<String> rankedTables = tableSelection.getRankedTableSummaries();
            String promptContext = buildPromptContext(matchedChunks, matchedTableNames, tableSelection, queryAnalysis);
            String retrieveSummary = buildRetrieveSummary(matchedChunks, matchedTableNames, rankedTables, resolution.isCacheHit());

            return RagContext.builder()
                    .enabled(true)
                    .cacheHit(resolution.isCacheHit())
                    .indexedChunkCount(resolution.getIndexedChunkCount())
                    .matchedChunkCount(matchedChunks.size())
                    .matchedTableNames(matchedTableNames)
                    .rankedTables(rankedTables)
                    .retrieveSummary(retrieveSummary)
                    .promptContext(promptContext)
                    .build();
        } catch (Exception e) {
            log.error("RAG检索失败, fileId={}, message={}", fileId, e.getMessage());
            log.error("RAG检索异常明细", e);
            return RagContext.empty("RAG检索失败，自动回退到原始AI流程");
        }
    }

    @Override
    public void invalidate(Long fileId) {
        if (fileId == null) {
            return;
        }
        ragVectorStoreService.deleteByFileId(fileId);
        buildLocks.remove(fileId);
        log.info("pgvector索引已失效, fileId={}", fileId);
    }

    private IndexResolution ensureVectorIndex(Long fileId) {
        if (ragVectorStoreService.existsByFileId(fileId)) {
            return new IndexResolution(true, ragVectorStoreService.countByFileId(fileId));
        }

        Object lock = buildLocks.computeIfAbsent(fileId, key -> new Object());
        synchronized (lock) {
            if (ragVectorStoreService.existsByFileId(fileId)) {
                return new IndexResolution(true, countIndexedChunks(fileId));
            }

            List<RagVectorChunk> chunks = buildVectorChunks(fileId);
            ragVectorStoreService.deleteByFileId(fileId);
            ragVectorStoreService.saveChunks(chunks);
            return new IndexResolution(false, chunks.size());
        }
    }

    private List<RagVectorChunk> buildVectorChunks(Long fileId) {
        FilesEntity file = fileMetaDataService.getFileById(fileId);
        List<FileTableMappingEntity> tableMappings = listTableMappings(fileId);
        List<SynonymGroup> synonymGroups = buildSynonymGroups();
        List<PreparedChunk> preparedChunks = new ArrayList<>();

        if (file != null) {
            preparedChunks.add(buildFileSummaryChunk(fileId, file, tableMappings, synonymGroups));
        }

        for (FileTableMappingEntity tableMapping : tableMappings) {
            List<FieldMappingEntity> fieldMappings = listFieldMappings(fileId, tableMapping.getTableName());
            List<Map<String, Object>> sampledRows = listRepresentativeSampleRows(tableMapping.getTableName());
            preparedChunks.add(buildTableSummaryChunk(fileId, file, tableMapping, fieldMappings, sampledRows, synonymGroups));
            preparedChunks.addAll(buildSampleChunks(fileId, tableMapping, fieldMappings, sampledRows, synonymGroups));
        }

        preparedChunks.removeIf(chunk -> !StringUtils.hasText(chunk.getContent()));
        embedChunks(preparedChunks);
        return preparedChunks.stream()
                .map(PreparedChunk::toVectorChunk)
                .toList();
    }

    private PreparedChunk buildFileSummaryChunk(
            Long fileId,
            FilesEntity file,
            List<FileTableMappingEntity> tableMappings,
            List<SynonymGroup> synonymGroups
    ) {
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
        content.append("销售术语别名：").append(formatSynonymGroups(synonymGroups)).append("\n");

        return PreparedChunk.builder()
                .chunkId("file-" + file.getId())
                .fileId(fileId)
                .chunkType(CHUNK_TYPE_FILE)
                .content(content.toString().trim())
                .keywords("")
                .canonicalTerms("")
                .build();
    }

    private PreparedChunk buildTableSummaryChunk(
            Long fileId,
            FilesEntity file,
            FileTableMappingEntity tableMapping,
            List<FieldMappingEntity> fieldMappings,
            List<Map<String, Object>> sampledRows,
            List<SynonymGroup> synonymGroups
    ) {
        String tableName = tableMapping.getTableName();
        long rowCount = countRows(tableName);
        List<String> headers = fieldMappings.stream()
                .map(FieldMappingEntity::getOriginalHeader)
                .filter(StringUtils::hasText)
                .toList();
        String originalHeaders = String.join(", ", headers);
        String fieldMappingSummary = fieldMappings.stream()
                .map(item -> defaultValue(item.getDbFieldName(), "unknown") + "->" + defaultValue(item.getOriginalHeader(), "unknown"))
                .collect(Collectors.joining(", "));
        ColumnInsight columnInsight = analyzeColumnInsight(headers, sampledRows, synonymGroups);
        Set<String> keywords = buildKeywords(tableMapping, fieldMappings, columnInsight, sampledRows, synonymGroups);
        Set<String> canonicalTerms = buildCanonicalTerms(keywords, synonymGroups);

        StringBuilder content = new StringBuilder();
        content.append("文件名：").append(file == null ? "未知文件" : defaultValue(file.getFileName(), "未知文件")).append("\n");
        content.append("Sheet名称：").append(defaultValue(tableMapping.getSheetName(), "未命名Sheet")).append("\n");
        content.append("数据库表：").append(defaultValue(tableName, "未知表")).append("\n");
        content.append("总行数：").append(rowCount).append("\n");
        content.append("原始表头：").append(defaultValue(originalHeaders, "无表头信息")).append("\n");
        content.append("字段映射：").append(defaultValue(fieldMappingSummary, "无字段映射")).append("\n");
        if (StringUtils.hasText(columnInsight.getBusinessSummary())) {
            content.append("业务维度摘要：").append(columnInsight.getBusinessSummary()).append("\n");
        }
        if (StringUtils.hasText(columnInsight.getTimeSummary())) {
            content.append("时间维度摘要：").append(columnInsight.getTimeSummary()).append("\n");
        }
        if (!canonicalTerms.isEmpty()) {
            content.append("归一化业务词：").append(String.join(", ", canonicalTerms)).append("\n");
        }

        return PreparedChunk.builder()
                .chunkId("table-" + tableName)
                .fileId(fileId)
                .tableName(tableName)
                .sheetName(tableMapping.getSheetName())
                .chunkType(CHUNK_TYPE_TABLE)
                .content(content.toString().trim())
                .keywords(joinTerms(keywords))
                .canonicalTerms(joinTerms(canonicalTerms))
                .businessSummary(columnInsight.getBusinessSummary())
                .timeSummary(columnInsight.getTimeSummary())
                .build();
    }

    private List<PreparedChunk> buildSampleChunks(
            Long fileId,
            FileTableMappingEntity tableMapping,
            List<FieldMappingEntity> fieldMappings,
            List<Map<String, Object>> sampledRows,
            List<SynonymGroup> synonymGroups
    ) {
        if (sampledRows.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> orderedHeaders = fieldMappings.stream()
                .map(FieldMappingEntity::getOriginalHeader)
                .filter(StringUtils::hasText)
                .toList();
        ColumnInsight columnInsight = analyzeColumnInsight(orderedHeaders, sampledRows, synonymGroups);
        Set<String> baseKeywords = buildKeywords(tableMapping, fieldMappings, columnInsight, sampledRows, synonymGroups);
        Set<String> baseCanonicalTerms = buildCanonicalTerms(baseKeywords, synonymGroups);

        List<PreparedChunk> chunks = new ArrayList<>();
        int rowsPerChunk = Math.max(1, ragProperties.getRowsPerChunk());
        int chunkIndex = 0;
        for (int i = 0; i < sampledRows.size(); i += rowsPerChunk) {
            int end = Math.min(i + rowsPerChunk, sampledRows.size());
            List<Map<String, Object>> subRows = sampledRows.subList(i, end);
            StringBuilder content = new StringBuilder();
            content.append("数据库表：").append(defaultValue(tableMapping.getTableName(), "未知表")).append("\n");
            content.append("Sheet名称：").append(defaultValue(tableMapping.getSheetName(), "未命名Sheet")).append("\n");
            if (!orderedHeaders.isEmpty()) {
                content.append("相关表头：").append(String.join(", ", orderedHeaders)).append("\n");
            }
            if (StringUtils.hasText(columnInsight.getTimeSummary())) {
                content.append("时间摘要：").append(columnInsight.getTimeSummary()).append("\n");
            }
            content.append("样例数据片段：").append(chunkIndex + 1).append("\n");
            for (int rowIndex = 0; rowIndex < subRows.size(); rowIndex++) {
                content.append("第").append(i + rowIndex + 1).append("行：")
                        .append(stringifyRow(subRows.get(rowIndex)))
                        .append("\n");
            }

            Set<String> chunkKeywords = new LinkedHashSet<>(baseKeywords);
            chunkKeywords.addAll(extractBusinessTermsFromRows(subRows));
            Set<String> chunkCanonicalTerms = new LinkedHashSet<>(baseCanonicalTerms);
            chunkCanonicalTerms.addAll(buildCanonicalTerms(chunkKeywords, synonymGroups));

            chunks.add(PreparedChunk.builder()
                    .chunkId("sample-" + tableMapping.getTableName() + "-" + chunkIndex)
                    .fileId(fileId)
                    .tableName(tableMapping.getTableName())
                    .sheetName(tableMapping.getSheetName())
                    .chunkType(CHUNK_TYPE_SAMPLE)
                    .content(content.toString().trim())
                    .keywords(joinTerms(chunkKeywords))
                    .canonicalTerms(joinTerms(chunkCanonicalTerms))
                    .businessSummary(columnInsight.getBusinessSummary())
                    .timeSummary(columnInsight.getTimeSummary())
                    .build());
            chunkIndex++;
        }
        return chunks;
    }

    private void embedChunks(List<PreparedChunk> chunks) {
        if (CollectionUtils.isEmpty(chunks)) {
            return;
        }
        List<String> texts = chunks.stream()
                .map(PreparedChunk::getContent)
                .toList();
        List<float[]> embeddings = embeddingModel.embed(texts);
        if (embeddings == null || embeddings.size() != chunks.size()) {
            throw new IllegalStateException("知识片段向量化失败，返回结果数量异常");
        }
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setEmbedding(embeddings.get(i));
        }
    }

    private QueryAnalysis analyzeQuery(String userInput, List<SynonymGroup> synonymGroups) {
        Set<String> rawTerms = extractBusinessTerms(userInput);
        Set<String> expandedTerms = new LinkedHashSet<>(rawTerms);
        Set<String> canonicalTerms = new LinkedHashSet<>();
        StringBuilder expandedQuery = new StringBuilder(userInput.trim());

        for (SynonymGroup group : synonymGroups) {
            if (group.matches(rawTerms, userInput)) {
                canonicalTerms.add(group.getCanonicalTerm());
                expandedTerms.addAll(group.getTerms());
                expandedQuery.append("\n业务术语归一化：")
                        .append(group.getCanonicalTerm())
                        .append("=")
                        .append(String.join("/", group.getTerms()));
            }
        }

        return new QueryAnalysis(userInput.trim(), expandedQuery.toString().trim(), rawTerms, expandedTerms, canonicalTerms);
    }

    private ScoredChunk scoreChunk(RagVectorChunk chunk, QueryAnalysis queryAnalysis) {
        double lexicalScore = lexicalScore(chunk, queryAnalysis);
        double finalScore = 0.65D + lexicalScore * ragProperties.getLexicalWeight();
        if (hasExactBusinessMatch(chunk, queryAnalysis)) {
            finalScore += ragProperties.getExactMatchBoost();
        }
        if (CHUNK_TYPE_TABLE.equals(chunk.getChunkType())
                && !CollectionUtils.isEmpty(queryAnalysis.getCanonicalTerms())
                && splitTerms(chunk.getCanonicalTerms()).containsAll(queryAnalysis.getCanonicalTerms())) {
            finalScore += ragProperties.getExactMatchBoost() / 2;
        }
        return new ScoredChunk(chunk, lexicalScore, finalScore);
    }

    private TableSelection selectTables(List<ScoredChunk> candidates, QueryAnalysis queryAnalysis) {
        Map<String, TableScore> tableScoreMap = new LinkedHashMap<>();
        for (ScoredChunk candidate : candidates) {
            String tableName = candidate.getChunk().getTableName();
            if (!StringUtils.hasText(tableName)) {
                continue;
            }
            tableScoreMap.computeIfAbsent(tableName, TableScore::new).addChunk(candidate, queryAnalysis);
        }

        List<TableScore> rankedScores = tableScoreMap.values()
                .stream()
                .sorted(Comparator.comparingDouble(TableScore::getScore).reversed())
                .toList();

        List<TableScore> matchedScores = rankedScores.stream()
                .filter(item -> item.getScore() >= ragProperties.getTableScoreThreshold())
                .limit(Math.max(1, ragProperties.getMaxMatchedTables()))
                .toList();

        if (matchedScores.isEmpty() && !rankedScores.isEmpty()) {
            matchedScores = rankedScores.stream()
                    .limit(Math.max(1, ragProperties.getMaxMatchedTables()))
                    .toList();
        }

        List<String> matchedTableNames = matchedScores.stream().map(TableScore::getTableName).toList();
        List<String> rankedTableSummaries = rankedScores.stream()
                .limit(Math.max(1, ragProperties.getMaxMatchedTables()))
                .map(item -> item.getTableName() + "(" + formatScore(item.getScore()) + ")")
                .toList();

        return new TableSelection(matchedTableNames, rankedTableSummaries);
    }

    private List<ScoredChunk> collectTopChunks(TableSelection tableSelection, List<ScoredChunk> candidates) {
        Set<String> selectedTables = new LinkedHashSet<>(tableSelection.getMatchedTableNames());
        return candidates.stream()
                .filter(chunk -> !StringUtils.hasText(chunk.getChunk().getTableName())
                        || selectedTables.contains(chunk.getChunk().getTableName()))
                .limit(Math.max(1, ragProperties.getTopK()))
                .collect(Collectors.toList());
    }

    private double lexicalScore(RagVectorChunk chunk, QueryAnalysis queryAnalysis) {
        Set<String> chunkTerms = splitTerms(chunk.getKeywords());
        Set<String> chunkCanonicalTerms = splitTerms(chunk.getCanonicalTerms());
        if (chunkTerms.isEmpty() || queryAnalysis.getExpandedTerms().isEmpty()) {
            return 0D;
        }
        long matchedKeywords = chunkTerms.stream().filter(queryAnalysis.getExpandedTerms()::contains).count();
        long matchedCanonicalTerms = chunkCanonicalTerms.stream().filter(queryAnalysis.getCanonicalTerms()::contains).count();
        double keywordCoverage = (double) matchedKeywords / Math.max(chunkTerms.size(), 1);
        double queryCoverage = (double) matchedKeywords / Math.max(queryAnalysis.getExpandedTerms().size(), 1);
        double canonicalCoverage = (double) matchedCanonicalTerms / Math.max(queryAnalysis.getCanonicalTerms().size(), 1);
        return Math.min(1D, keywordCoverage * 0.55D + queryCoverage * 0.25D + canonicalCoverage * 0.20D);
    }

    private boolean hasExactBusinessMatch(RagVectorChunk chunk, QueryAnalysis queryAnalysis) {
        Set<String> chunkTerms = splitTerms(chunk.getKeywords());
        Set<String> chunkCanonicalTerms = splitTerms(chunk.getCanonicalTerms());
        for (String term : queryAnalysis.getExpandedTerms()) {
            if (chunkTerms.contains(term)) {
                return true;
            }
        }
        for (String canonicalTerm : queryAnalysis.getCanonicalTerms()) {
            if (chunkCanonicalTerms.contains(canonicalTerm)) {
                return true;
            }
        }
        return false;
    }

    private String buildPromptContext(
            List<ScoredChunk> matchedChunks,
            List<String> matchedTableNames,
            TableSelection tableSelection,
            QueryAnalysis queryAnalysis
    ) {
        StringBuilder promptContext = new StringBuilder();
        promptContext.append("以下内容来自当前Excel文件在pgvector中的销售报表RAG检索结果，请优先依据这些事实理解用户问题、表名、字段含义和时间范围：\n");
        if (!matchedTableNames.isEmpty()) {
            promptContext.append("优先候选表：").append(String.join(", ", matchedTableNames)).append("\n");
        }
        if (!tableSelection.getRankedTableSummaries().isEmpty()) {
            promptContext.append("表级得分排序：").append(String.join(", ", tableSelection.getRankedTableSummaries())).append("\n");
        }
        if (!queryAnalysis.getCanonicalTerms().isEmpty()) {
            promptContext.append("用户业务词归一化：").append(String.join(", ", queryAnalysis.getCanonicalTerms())).append("\n");
        }
        int index = 1;
        for (ScoredChunk matchedChunk : matchedChunks) {
            promptContext.append("片段").append(index++)
                    .append(" [表=").append(defaultValue(matchedChunk.getChunk().getTableName(), "文件级"))
                    .append(", 类型=").append(matchedChunk.getChunk().getChunkType())
                    .append(", 综合得分=").append(formatScore(matchedChunk.getFinalScore()))
                    .append(", 关键词得分=").append(formatScore(matchedChunk.getLexicalScore()))
                    .append("]\n")
                    .append(matchedChunk.getChunk().getContent())
                    .append("\n");
        }
        return promptContext.toString().trim();
    }

    private String buildRetrieveSummary(
            List<ScoredChunk> matchedChunks,
            List<String> matchedTableNames,
            List<String> rankedTables,
            boolean cacheHit
    ) {
        String tableText = matchedTableNames.isEmpty() ? "未定位到具体表" : String.join(", ", matchedTableNames);
        String rankedTableText = rankedTables.isEmpty() ? "无" : String.join(", ", rankedTables);
        return String.format(Locale.ROOT,
                "%spgvector索引，命中%d个片段，优先表：%s，表级排序：%s",
                cacheHit ? "复用" : "构建",
                matchedChunks.size(),
                tableText,
                rankedTableText
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

    private List<Map<String, Object>> listRepresentativeSampleRows(String tableName) {
        int sampleLimit = Math.max(1, ragProperties.getSampleRowsPerTable());
        try {
            List<Map<String, Object>> headRows = jdbcTemplate.queryForList("select * from `" + tableName + "` limit " + sampleLimit);
            LinkedHashMap<String, Map<String, Object>> uniqueRows = new LinkedHashMap<>();
            putRows(uniqueRows, fileMetaDataService.mapQuery(headRows, tableName));

            if (sampleLimit > 2) {
                int tailLimit = Math.max(1, sampleLimit / 3);
                List<Map<String, Object>> tailRows = jdbcTemplate.queryForList(
                        "select * from `" + tableName + "` order by 1 desc limit " + tailLimit
                );
                putRows(uniqueRows, fileMetaDataService.mapQuery(tailRows, tableName));
            }

            if (sampleLimit > 4) {
                List<Map<String, Object>> randomRows = jdbcTemplate.queryForList(
                        "select * from `" + tableName + "` order by rand() limit " + Math.max(1, sampleLimit / 3)
                );
                putRows(uniqueRows, fileMetaDataService.mapQuery(randomRows, tableName));
            }

            return uniqueRows.values().stream().limit(sampleLimit).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("查询样例数据失败, tableName={}, message={}", tableName, e.getMessage());
            return Collections.emptyList();
        }
    }

    private void putRows(LinkedHashMap<String, Map<String, Object>> uniqueRows, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            uniqueRows.putIfAbsent(row.toString(), row);
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

    private Set<String> buildKeywords(
            FileTableMappingEntity tableMapping,
            List<FieldMappingEntity> fieldMappings,
            ColumnInsight columnInsight,
            List<Map<String, Object>> sampledRows,
            List<SynonymGroup> synonymGroups
    ) {
        Set<String> keywords = new LinkedHashSet<>();
        addTerm(keywords, tableMapping.getTableName());
        addTerm(keywords, tableMapping.getSheetName());
        for (FieldMappingEntity fieldMapping : fieldMappings) {
            addTerm(keywords, fieldMapping.getOriginalHeader());
            addTerm(keywords, fieldMapping.getDbFieldName());
        }
        if (columnInsight != null) {
            addAllTerms(keywords, columnInsight.getDimensionHeaders());
            addAllTerms(keywords, columnInsight.getMeasureHeaders());
            addAllTerms(keywords, columnInsight.getTimeHeaders());
            addAllTerms(keywords, columnInsight.getTimeLabels());
        }
        keywords.addAll(extractBusinessTermsFromRows(sampledRows));
        keywords.addAll(buildCanonicalTerms(keywords, synonymGroups));
        return keywords;
    }

    private Set<String> buildCanonicalTerms(Collection<String> keywords, List<SynonymGroup> synonymGroups) {
        Set<String> canonicalTerms = new LinkedHashSet<>();
        if (CollectionUtils.isEmpty(keywords)) {
            return canonicalTerms;
        }
        Set<String> normalizedTerms = keywords.stream()
                .map(this::normalizeTerm)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        for (SynonymGroup group : synonymGroups) {
            for (String term : normalizedTerms) {
                if (group.contains(term)) {
                    canonicalTerms.add(group.getCanonicalTerm());
                    break;
                }
            }
        }
        return canonicalTerms;
    }

    private Set<String> extractBusinessTermsFromRows(List<Map<String, Object>> rows) {
        Set<String> terms = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                addTerm(terms, entry.getKey());
                String text = Objects.toString(entry.getValue(), "");
                if (looksLikeBusinessText(text)) {
                    addTerm(terms, text);
                }
            }
        }
        return terms;
    }

    private Set<String> extractBusinessTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) {
            return terms;
        }
        Matcher matcher = BUSINESS_TERM_PATTERN.matcher(text);
        while (matcher.find()) {
            addTerm(terms, matcher.group());
        }
        return terms;
    }

    private ColumnInsight analyzeColumnInsight(
            List<String> headers,
            List<Map<String, Object>> sampledRows,
            List<SynonymGroup> synonymGroups
    ) {
        List<String> dimensionHeaders = new ArrayList<>();
        List<String> measureHeaders = new ArrayList<>();
        List<String> timeHeaders = new ArrayList<>();
        Set<String> timeLabels = new LinkedHashSet<>();
        Map<String, Set<String>> dimensionSamples = new LinkedHashMap<>();

        for (String header : headers) {
            if (!StringUtils.hasText(header)) {
                continue;
            }

            List<Object> values = sampledRows.stream()
                    .map(row -> row.get(header))
                    .filter(Objects::nonNull)
                    .toList();
            boolean numericLike = isNumericColumn(values);
            boolean timeLike = isTimeHeader(header) || containsDateValue(values);

            if (timeLike) {
                timeHeaders.add(header);
                timeLabels.addAll(extractTimeLabels(values));
            } else if (numericLike) {
                measureHeaders.add(header);
            } else {
                dimensionHeaders.add(header);
                dimensionSamples.put(header, extractDistinctSamples(values));
            }
        }

        String businessSummary = buildBusinessSummary(dimensionHeaders, measureHeaders, dimensionSamples, synonymGroups);
        String timeSummary = buildTimeSummary(timeHeaders, timeLabels);
        return new ColumnInsight(dimensionHeaders, measureHeaders, timeHeaders, timeLabels, businessSummary, timeSummary);
    }

    private boolean isNumericColumn(List<Object> values) {
        if (values.isEmpty()) {
            return false;
        }
        int numericCount = 0;
        for (Object value : values) {
            if (value instanceof Number) {
                numericCount++;
                continue;
            }
            String text = Objects.toString(value, "").replace(",", "").replace("%", "").trim();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            try {
                Double.parseDouble(text);
                numericCount++;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return numericCount > 0;
    }

    private boolean isTimeHeader(String header) {
        String normalized = normalizeTerm(header);
        return normalized.contains("日期")
                || normalized.contains("时间")
                || normalized.contains("月份")
                || normalized.contains("月度")
                || normalized.contains("季度")
                || normalized.contains("年度")
                || normalized.contains("year")
                || normalized.contains("month")
                || normalized.contains("date");
    }

    private boolean containsDateValue(List<Object> values) {
        for (Object value : values) {
            if (parseTemporalValue(value) != null) {
                return true;
            }
        }
        return false;
    }

    private Set<String> extractTimeLabels(List<Object> values) {
        Set<String> labels = new LinkedHashSet<>();
        for (Object value : values) {
            TemporalValue temporalValue = parseTemporalValue(value);
            if (temporalValue != null && StringUtils.hasText(temporalValue.getLabel())) {
                labels.add(temporalValue.getLabel());
            }
        }
        return labels;
    }

    private Set<String> extractDistinctSamples(List<Object> values) {
        return values.stream()
                .map(this::shortenValue)
                .filter(this::looksLikeBusinessText)
                .limit(3)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String buildBusinessSummary(
            List<String> dimensionHeaders,
            List<String> measureHeaders,
            Map<String, Set<String>> dimensionSamples,
            List<SynonymGroup> synonymGroups
    ) {
        List<String> segments = new ArrayList<>();
        if (!dimensionHeaders.isEmpty()) {
            segments.add("维度字段=" + String.join(", ", dimensionHeaders));
        }
        if (!measureHeaders.isEmpty()) {
            segments.add("指标字段=" + String.join(", ", measureHeaders));
        }
        for (Map.Entry<String, Set<String>> entry : dimensionSamples.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                segments.add(entry.getKey() + "样例=" + String.join("/", entry.getValue()));
            }
        }
        Set<String> canonicalTerms = buildCanonicalTerms(concatCollections(dimensionHeaders, measureHeaders), synonymGroups);
        if (!canonicalTerms.isEmpty()) {
            segments.add("业务归类=" + String.join(", ", canonicalTerms));
        }
        return String.join("；", segments);
    }

    private String buildTimeSummary(List<String> timeHeaders, Set<String> timeLabels) {
        if (timeHeaders.isEmpty() && timeLabels.isEmpty()) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        if (!timeHeaders.isEmpty()) {
            segments.add("时间字段=" + String.join(", ", timeHeaders));
        }
        if (!timeLabels.isEmpty()) {
            segments.add("覆盖时间=" + String.join(", ", timeLabels.stream().limit(6).toList()));
        }
        return String.join("；", segments);
    }

    private <T> Collection<T> concatCollections(Collection<T> first, Collection<T> second) {
        List<T> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private TemporalValue parseTemporalValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return new TemporalValue(localDate.atStartOfDay(), localDate.toString());
        }
        if (value instanceof LocalDateTime localDateTime) {
            return new TemporalValue(localDateTime, localDateTime.toLocalDate().toString());
        }
        String text = Objects.toString(value, "").trim();
        if (!StringUtils.hasText(text)) {
            return null;
        }

        String normalized = text.replace("年", "-")
                .replace("月", "-")
                .replace("日", "")
                .replace("/", "-")
                .replace(".", "-")
                .replaceAll("\\s+", " ");
        if (normalized.matches("\\d{4}-\\d{1,2}$")) {
            try {
                YearMonth yearMonth = YearMonth.parse(normalized, DateTimeFormatter.ofPattern("yyyy-M"));
                return new TemporalValue(yearMonth.atDay(1).atStartOfDay(), yearMonth.toString());
            } catch (DateTimeParseException ignored) {
                // ignore
            }
        }
        if (normalized.matches("\\d{4}Q[1-4]")) {
            return new TemporalValue(null, normalized);
        }

        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                if (formatter.toString().contains("H")) {
                    LocalDateTime dateTime = LocalDateTime.parse(normalized, formatter);
                    return new TemporalValue(dateTime, dateTime.toLocalDate().toString());
                }
                LocalDate date = LocalDate.parse(normalized, formatter);
                return new TemporalValue(date.atStartOfDay(), date.toString());
            } catch (DateTimeParseException ignored) {
                // ignore
            }
        }
        return null;
    }

    private List<SynonymGroup> buildSynonymGroups() {
        return ragProperties.getSalesSynonymGroups().stream()
                .map(this::toSynonymGroup)
                .filter(Objects::nonNull)
                .toList();
    }

    private SynonymGroup toSynonymGroup(String rawGroup) {
        if (!StringUtils.hasText(rawGroup)) {
            return null;
        }
        List<String> terms = Arrays.stream(rawGroup.split("[,，、/]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (terms.isEmpty()) {
            return null;
        }
        String canonicalTerm = terms.get(0);
        Set<String> normalizedTerms = terms.stream()
                .map(this::normalizeTerm)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new SynonymGroup(canonicalTerm, terms, normalizedTerms);
    }

    private String formatSynonymGroups(List<SynonymGroup> synonymGroups) {
        return synonymGroups.stream()
                .map(group -> group.getCanonicalTerm() + "=" + String.join("/", group.getTerms()))
                .collect(Collectors.joining("；"));
    }

    private String normalizeTerm(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replace("（", "(")
                .replace("）", ")")
                .replaceAll("[\\s\\p{Punct}]+", "");
    }

    private boolean looksLikeBusinessText(String text) {
        return StringUtils.hasText(text)
                && text.length() <= 40
                && BUSINESS_TERM_PATTERN.matcher(text).find()
                && !text.matches("\\d+(\\.\\d+)?");
    }

    private void addAllTerms(Set<String> terms, Collection<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            addTerm(terms, value);
        }
    }

    private void addTerm(Set<String> terms, String value) {
        String normalized = normalizeTerm(value);
        if (StringUtils.hasText(normalized) && normalized.length() >= 2) {
            terms.add(normalized);
        }
    }

    private String joinTerms(Collection<String> terms) {
        return terms == null || terms.isEmpty() ? "" : String.join(",", terms);
    }

    private Set<String> splitTerms(String text) {
        if (!StringUtils.hasText(text)) {
            return Collections.emptySet();
        }
        return Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String formatScore(double score) {
        return String.format(Locale.ROOT, "%.4f", score);
    }

    private String defaultValue(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    @Getter
    @AllArgsConstructor
    private static class IndexResolution {
        private final boolean cacheHit;
        private final int indexedChunkCount;
    }

    @Getter
    @AllArgsConstructor
    private static class QueryAnalysis {
        private final String originalQuery;
        private final String expandedQuery;
        private final Set<String> rawTerms;
        private final Set<String> expandedTerms;
        private final Set<String> canonicalTerms;
    }

    @Getter
    @AllArgsConstructor
    private static class TableSelection {
        private final List<String> matchedTableNames;
        private final List<String> rankedTableSummaries;
    }

    @Getter
    @AllArgsConstructor
    private static class ScoredChunk {
        private final RagVectorChunk chunk;
        private final double lexicalScore;
        private final double finalScore;
    }

    @Getter
    private static class TableScore {
        private final String tableName;
        private double score;
        private double bestChunkScore;
        private int chunkCount;
        private final Set<String> matchedTerms = new LinkedHashSet<>();
        private final Set<String> matchedCanonicalTerms = new LinkedHashSet<>();

        private TableScore(String tableName) {
            this.tableName = tableName;
        }

        private void addChunk(ScoredChunk chunk, QueryAnalysis queryAnalysis) {
            chunkCount++;
            bestChunkScore = Math.max(bestChunkScore, chunk.getFinalScore());
            score += chunk.getFinalScore();
            Set<String> chunkTerms = splitTermsStatic(chunk.getChunk().getKeywords());
            Set<String> chunkCanonicalTerms = splitTermsStatic(chunk.getChunk().getCanonicalTerms());
            for (String term : queryAnalysis.getExpandedTerms()) {
                if (chunkTerms.contains(term)) {
                    matchedTerms.add(term);
                }
            }
            for (String canonicalTerm : queryAnalysis.getCanonicalTerms()) {
                if (chunkCanonicalTerms.contains(canonicalTerm)) {
                    matchedCanonicalTerms.add(canonicalTerm);
                }
            }
        }

        private double getScore() {
            double coverageBoost = Math.min(0.20D, matchedTerms.size() * 0.03D + matchedCanonicalTerms.size() * 0.05D);
            return bestChunkScore * 0.55D + (score / Math.max(1, chunkCount)) * 0.45D + coverageBoost;
        }

        private static Set<String> splitTermsStatic(String text) {
            if (!StringUtils.hasText(text)) {
                return Collections.emptySet();
            }
            return Arrays.stream(text.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    @Getter
    @AllArgsConstructor
    private static class ColumnInsight {
        private final List<String> dimensionHeaders;
        private final List<String> measureHeaders;
        private final List<String> timeHeaders;
        private final Set<String> timeLabels;
        private final String businessSummary;
        private final String timeSummary;
    }

    @Getter
    @AllArgsConstructor
    private static class SynonymGroup {
        private final String canonicalTerm;
        private final List<String> terms;
        private final Set<String> normalizedTerms;

        private boolean matches(Set<String> rawTerms, String rawQuery) {
            Set<String> normalizedRawTerms = rawTerms.stream()
                    .map(term -> term.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            for (String normalizedTerm : normalizedTerms) {
                if (normalizedRawTerms.contains(normalizedTerm)) {
                    return true;
                }
                if (normalizeInput(rawQuery).contains(normalizedTerm)) {
                    return true;
                }
            }
            return false;
        }

        private boolean contains(String normalizedTerm) {
            return normalizedTerms.contains(normalizedTerm);
        }

        private String normalizeInput(String input) {
            return input == null ? "" : input.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", "");
        }
    }

    @Getter
    @AllArgsConstructor
    private static class TemporalValue {
        private final LocalDateTime dateTime;
        private final String label;
    }

    @Getter
    @lombok.Setter
    @lombok.Builder
    private static class PreparedChunk {
        private String chunkId;
        private Long fileId;
        private String tableName;
        private String sheetName;
        private String chunkType;
        private String content;
        private String keywords;
        private String canonicalTerms;
        private String businessSummary;
        private String timeSummary;
        private float[] embedding;

        private RagVectorChunk toVectorChunk() {
            return RagVectorChunk.builder()
                    .chunkId(chunkId)
                    .fileId(fileId)
                    .tableName(tableName)
                    .sheetName(sheetName)
                    .chunkType(chunkType)
                    .content(content)
                    .keywords(keywords)
                    .canonicalTerms(canonicalTerms)
                    .businessSummary(businessSummary)
                    .timeSummary(timeSummary)
                    .embedding(embedding)
                    .build();
        }
    }
}
