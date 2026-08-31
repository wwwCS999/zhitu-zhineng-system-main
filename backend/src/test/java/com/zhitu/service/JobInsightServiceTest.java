package com.zhitu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhitu.ai.AiClient;
import com.zhitu.common.Jsons;
import com.zhitu.dto.JobExtraction;
import com.zhitu.repository.Store;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobInsightServiceTest {

    @Mock Store store;
    @Mock AiClient ai;

    private JobInsightService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        service = new JobInsightService(store, new SkillOntologyService(store), ai, new Jsons(mapper));
    }

    @Test
    @SuppressWarnings("unchecked")
    void benchmarkContainsAtLeastOneHundredGoldJdCasesAndPassesNinetyPercentSkillF1() throws Exception {
        when(ai.enabled()).thenReturn(false);

        List<Map<String, Object>> goldCases = mapper.readValue(
                Path.of("../data/gold-extractions.json").toFile(),
                List.class
        );
        List<CSVRecord> rows;
        try (Reader reader = Files.newBufferedReader(Path.of("../data/sample-jd-120.csv"))) {
            rows = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setTrim(true)
                    .get()
                    .parse(reader)
                    .getRecords();
        }

        assertTrue(rows.size() >= 100, "JD 测试样本必须不少于 100 条");
        assertTrue(goldCases.size() >= 100, "JD 金标样本必须不少于 100 条");

        int tp = 0;
        int fp = 0;
        int fn = 0;
        int checked = Math.min(rows.size(), goldCases.size());
        for (int i = 0; i < checked; i++) {
            CSVRecord row = rows.get(i);
            Map<String, Object> gold = goldCases.get(i);
            JobExtraction extraction = service.extract(
                    row.get("招聘岗位"),
                    row.get("招聘岗位") + "\n" + row.get("职位描述"),
                    row.get("初级分类"),
                    row.get("要求经验")
            );
            Set<String> predicted = new LinkedHashSet<>();
            predicted.addAll(extraction.requiredSkills());
            predicted.addAll(extraction.bonusSkills());
            Set<String> truth = new LinkedHashSet<>((List<String>) gold.get("requiredSkills"));

            for (String skill : predicted) {
                if (truth.contains(skill)) tp++;
                else fp++;
            }
            for (String skill : truth) {
                if (!predicted.contains(skill)) fn++;
            }
            assertFalse(extraction.roleName().isBlank());
            assertTrue(extraction.confidence() >= 0.80D, String.valueOf(extraction));
        }

        double precision = tp / (double) Math.max(1, tp + fp);
        double recall = tp / (double) Math.max(1, tp + fn);
        double f1 = 2D * precision * recall / Math.max(0.0001D, precision + recall);
        assertTrue(f1 >= 0.90D, "JD 技能抽取 F1 应 >= 90%，实际为 " + f1);
    }

    @Test
    void deepseekCandidatesAreFilteredBySourceEvidenceBeforeEnteringExtraction() {
        when(ai.enabled()).thenReturn(true);
        when(ai.modelName()).thenReturn("deepseek-chat");
        when(ai.complete(anyString(), anyString(), eq("deepseek-chat"), anyInt(), eq(0D)))
                .thenReturn(Optional.of("""
                        {
                          "roleName": "Java 开发工程师",
                          "responsibilities": ["负责企业知识库后端服务开发"],
                          "requiredSkills": ["Java", "Spring Boot", "MySQL", "Kubernetes", "沟通能力"],
                          "bonusSkills": ["Redis", "区块链"],
                          "scenarios": ["企业知识库", "元宇宙招聘"]
                        }
                        """));

        String jd = "负责企业知识库后端服务开发。要求掌握 Java、Spring Boot、MySQL，熟悉 Redis 者优先。";
        JobExtraction extraction = service.extract("Java 开发工程师", jd, "后端开发", "初级");
        Set<String> allSkills = new LinkedHashSet<>();
        allSkills.addAll(extraction.requiredSkills());
        allSkills.addAll(extraction.bonusSkills());

        assertTrue(allSkills.containsAll(List.of("Java", "Spring Boot", "MySQL", "Redis")));
        assertFalse(allSkills.contains("Kubernetes"));
        assertFalse(allSkills.contains("区块链"));
        assertFalse(allSkills.contains("沟通能力"));
        Map<String, Object> rationale = extraction.rationale();
        assertTrue(((Number) rationale.get("llmBlockedUnsupportedItems")).intValue() > 0);
        assertTrue(((Number) rationale.get("blockedCandidateRate")).doubleValue() > 0D);
        assertTrue(((Number) rationale.get("hallucinationRisk")).doubleValue() <= 0.10D);
    }

    @Test
    void evaluationSummaryExposesCompetitionVerificationPlan() {
        Map<String, Object> summary = service.parserEvaluationSummary();

        assertTrue(((Number) summary.get("jdCases")).intValue() >= 100);
        assertTrue(((Number) summary.get("targetAccuracy")).doubleValue() >= 0.90D);
        assertTrue(((Number) summary.get("hallucinationGate")).doubleValue() <= 0.10D);
        assertTrue(String.valueOf(summary.get("parserVersion")).contains("evidence"));
    }
}
