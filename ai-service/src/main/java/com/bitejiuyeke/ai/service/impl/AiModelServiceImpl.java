package com.bitejiuyeke.ai.service.impl;

import com.bitejiuyeke.ai.service.AiModelService;
import com.bitejiuyeke.ai.service.SQLGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI大模型服务的实现类
 */
@Service
@Slf4j
public class AiModelServiceImpl implements AiModelService {

    @Autowired
    private SQLGenerationService sqlGenerationService;


    @Override
    public String generateAiResponse(String prompt, List<Map<String, Object>> resultData, String ragContext) {
        String finalPrompt = buildPrompt(prompt, resultData, ragContext);
        return cleanModelOutput(sqlGenerationService.get(finalPrompt));
    }

    @Override
    public List<String> getFieldsFromUserInput(String userInput, String ragContext) {
        String prompt = String.format("请从用户的问题中提取关键字段（列名、指标名、业务对象）\n"
                        + "要求：\n"
                        + "1. 只提取用户问题里真正相关的字段，不要扩写\n"
                        + "2. 如果有多个字段，请用英文逗号分隔\n"
                        + "3. 如果完全提取不到，直接返回 无\n"
                        + "4. 对销售报表场景，优先识别销售额、销量、毛利、客户、区域、产品、时间等业务词\n"
                        + "5. 不要补充解释，不要输出额外内容\n"
                        + "实际用户输入：%s\n",
                userInput
        );

        String response = cleanModelOutput(sqlGenerationService.get(buildPrompt(prompt, null, ragContext)));
        if (!StringUtils.hasText(response) || "无".equals(response)) {
            return new ArrayList<>();
        }

        String[] parts = response.split("[,，、\\n]+");
        List<String> results = new ArrayList<>();
        for (String part : parts) {
            String field = cleanModelOutput(part);
            if (StringUtils.hasText(field) && !"无".equals(field)) {
                results.add(field);
            }
        }
        return results;
    }

    @Override
    public String getSql(String userInput, String tableName, List<Map<String, Object>> tableStructure, String ragContext) {
        List<String> headers = tableStructure.stream()
                .map(row -> (String) row.get("Field"))
                .filter(StringUtils::hasText)
                .toList();
        String prompt = String.format("" +
                        "你是一个SQL专家，需要根据用户需求生成MySQL查询语句。\n" +
                        "表名：%s\n" +
                        "表结构：%s\n" +
                        "用户需求：%s\n" +
                        "要求：\n" +
                        "1. 只输出可直接执行的SQL，不要解释\n" +
                        "2. 优先依据当前表和RAG上下文中的字段、样例数据来生成SQL\n" +
                        "3. 当前主要用于销售报表，请重点理解销售额、销量、毛利、客户、区域、产品、月份、季度等业务词\n" +
                        "4. 查询条件尽量贴近用户描述，文本查询优先考虑模糊匹配\n" +
                        "5. 如果用户要求排行、汇总、趋势、对比，请优先使用group by、sum、count、avg、order by等聚合语义\n" +
                        "6. 不要输出Markdown代码块\n",
                tableName, headers, userInput
        );

        String response = cleanSql(sqlGenerationService.get(buildPrompt(prompt, null, ragContext)));
        log.info("最终生成的查询SQL: {}", response);
        return response;
    }

    @Override
    public String getUpdateSql(String userInput, String tableName, List<Map<String, Object>> tableStructure, String ragContext) {
        List<String> headers = tableStructure.stream()
                .map(row -> (String) row.get("Field"))
                .filter(StringUtils::hasText)
                .toList();
        String prompt = String.format("" +
                        "你是一个SQL专家，需要根据用户需求生成MySQL修改语句。\n" +
                        "表名：%s\n" +
                        "表结构：%s\n" +
                        "用户需求：%s\n" +
                        "要求：\n" +
                        "1. 只输出可直接执行的SQL，不要解释\n" +
                        "2. 只允许生成update语句，不要生成insert、delete、drop、truncate\n" +
                        "3. 优先依据当前表和RAG上下文中的字段、样例数据来生成SQL\n" +
                        "4. 当前主要用于销售报表，请重点理解销售额、销量、毛利、客户、区域、产品、月份等业务词\n" +
                        "5. where条件要尽量精确，避免误修改整表数据\n" +
                        "6. 不要输出Markdown代码块\n",
                tableName, headers, userInput
        );

        String response = cleanSql(sqlGenerationService.get(buildPrompt(prompt, null, ragContext)));
        log.info("最终生成的修改SQL: {}", response);
        return response;
    }

    private String buildPrompt(String prompt, List<Map<String, Object>> resultData, String ragContext) {
        StringBuilder finalPrompt = new StringBuilder();
        finalPrompt.append(prompt).append("\n");

        if (StringUtils.hasText(ragContext)) {
            finalPrompt.append("\n【RAG检索到的当前文件上下文】\n")
                    .append(ragContext)
                    .append("\n");
        }

        if (!CollectionUtils.isEmpty(resultData)) {
            finalPrompt.append("\n【查询结果样例】\n");
            int limit = Math.min(5, resultData.size());
            for (int i = 0; i < limit; i++) {
                finalPrompt.append("第").append(i + 1).append("条：").append(resultData.get(i)).append("\n");
            }
            finalPrompt.append("结果总数：").append(resultData.size()).append("\n");
        }

        return finalPrompt.toString().trim();
    }

    private String cleanSql(String response) {
        String result = cleanModelOutput(response);
        result = result.replaceAll("(?is)^```sql\\s*", "");
        result = result.replaceAll("(?is)^```\\s*", "");
        result = result.replaceAll("(?is)\\s*```$", "");
        result = result.replaceFirst("(?i)^sql\\s*[:：]\\s*", "");
        return result.trim();
    }

    private String cleanModelOutput(String response) {
        if (!StringUtils.hasText(response)) {
            return "";
        }
        return response
                .replace("`", "")
                .replace("\r", "")
                .trim();
    }
}
