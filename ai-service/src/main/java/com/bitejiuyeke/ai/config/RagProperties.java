package com.bitejiuyeke.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RAG相关配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "chat2excel.rag")
public class RagProperties {

    /**
     * 是否启用RAG
     */
    private boolean enabled = true;

    /**
     * 每次检索返回的片段数量
     */
    private int topK = 4;

    /**
     * 相似度阈值
     */
    private double similarityThreshold = 0.45D;

    /**
     * 每张表最多采样多少行数据来构建知识片段
     */
    private int sampleRowsPerTable = 15;

    /**
     * 每个数据片段放多少行
     */
    private int rowsPerChunk = 5;

    /**
     * 向量索引缓存分钟数
     */
    private long cacheMinutes = 30L;

    /**
     * 表级召回结果最多保留多少张表
     */
    private int maxMatchedTables = 3;

    /**
     * 语义召回得分权重
     */
    private double semanticWeight = 0.65D;

    /**
     * 关键词召回得分权重
     */
    private double lexicalWeight = 0.35D;

    /**
     * 表级得分阈值
     */
    private double tableScoreThreshold = 0.55D;

    /**
     * 精确命中表头或业务词时的加分
     */
    private double exactMatchBoost = 0.12D;

    /**
     * 销售报表常见业务术语别名组
     */
    private List<String> salesSynonymGroups = new ArrayList<>(Arrays.asList(
            "销售额,成交额,成交金额,GMV,营收,收入",
            "销量,销售量,件数,出货量,销售件数",
            "毛利,利润,利润额,毛利润",
            "客户,商户,经销商,代理商,门店",
            "区域,大区,省区,片区,城市,地区",
            "产品,商品,品类,SKU,型号,品牌",
            "渠道,渠道类型,销售渠道,销售通路",
            "销售员,业务员,客户经理,招商主管",
            "日期,时间,月份,月度,季度,年度"
    ));
}
