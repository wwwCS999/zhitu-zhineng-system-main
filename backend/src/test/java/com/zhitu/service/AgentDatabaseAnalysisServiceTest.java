package com.zhitu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhitu.ai.AiClient;
import com.zhitu.repository.RawDatabaseClient;
import com.zhitu.repository.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentDatabaseAnalysisServiceTest {

    @Mock RawDatabaseClient raw;
    @Mock Store store;
    @Mock RawJobGovernanceService governance;
    @Mock AiClient ai;

    private AgentDatabaseAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new AgentDatabaseAnalysisService(raw, store, governance, ai, new ObjectMapper());
    }

    @Test
    void twentyFiveTopRoleQuestionRunsReal2025Aggregation() {
        when(raw.list(argThat(sql -> sql != null && sql.contains("GROUP BY title_standard")), any(Object[].class)))
                .thenReturn(List.of(
                        Map.of("role_name", "Java开发工程师", "job_count", 12600L),
                        Map.of("role_name", "算法工程师", "job_count", 9800L)
                ));

        AgentDatabaseAnalysisService.AnalysisResult result =
                service.analyze("请根据数据库里面25级最多数量的岗位得出可能的预测");

        assertTrue(result.evidence().stream().anyMatch(
                row -> "TOP_ROLES".equals(row.get("queryType")) && Integer.valueOf(2025).equals(row.get("year"))
        ));
        assertTrue(result.evidence().stream().anyMatch(
                row -> "Java开发工程师".equals(row.get("role_name"))
        ));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(raw).list(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("first_half_count"));
        assertTrue(sql.getValue().contains("second_half_count"));
        assertEquals(2025, args.getValue()[0]);
        assertEquals(10, args.getValue()[1]);
    }

    @Test
    void learningQuestionReturnsExecutableSystemPolicyAndGapContext() {
        when(store.list(argThat(sql -> sql != null && sql.contains("FROM learning_path")), any()))
                .thenReturn(List.of(Map.of(
                        "title", "Java工程师12周路径",
                        "objective", "补齐Spring与云原生"
                )));
        when(store.list(argThat(sql -> sql != null && sql.contains("FROM match_report")), any()))
                .thenReturn(List.of(Map.of(
                        "role_name", "Java开发工程师",
                        "missing_skills", "[\"Spring\",\"Docker\"]"
                )));

        AgentDatabaseAnalysisService.AnalysisResult result =
                service.analyze("如何根据技能差距生成学习路径？");

        assertTrue(result.evidence().stream().anyMatch(
                row -> "learning_path_generation_policy".equals(row.get("context_type"))
        ));
        assertTrue(result.evidence().stream().anyMatch(
                row -> row.containsKey("missing_skills")
        ));
    }

    @Test
    void openEndedQuestionUsesModelPlanButNeverModelSql() {
        when(ai.enabled()).thenReturn(true);
        when(ai.complete(anyString(), anyString())).thenReturn(Optional.of("""
                {"queries":[{"queryType":"TOP_CITIES","year":2025,"roleKeyword":"Java","city":"","limit":5}]}
                """));
        when(raw.list(argThat(sql -> sql != null && sql.contains("GROUP BY city")), any(Object[].class)))
                .thenReturn(List.of(Map.of("city", "北京", "job_count", 3200L)));

        AgentDatabaseAnalysisService.AnalysisResult result =
                service.analyze("帮我看看去年 Java 人才主要集中在哪些地方");

        assertTrue(result.plannedByModel());
        assertTrue(result.evidence().stream().anyMatch(row -> "北京".equals(row.get("city"))));
        verify(raw).list(
                argThat(sql -> sql.startsWith("SELECT") && !sql.contains("DELETE") && !sql.contains("UPDATE")),
                any(Object[].class)
        );
    }
}
