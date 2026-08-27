package com.zhitu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhitu.ai.AiClient;
import com.zhitu.dto.AgentAnswer;
import com.zhitu.repository.RawDatabaseClient;
import com.zhitu.repository.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentQuestionAnswerServiceTest {

    @Mock Store store;
    @Mock RawDatabaseClient raw;
    @Mock RawJobGovernanceService governance;
    @Mock AgentDatabaseAnalysisService databaseAnalysis;
    @Mock EmergingRoleService emergingRoleService;
    @Mock EvolutionService evolutionService;
    @Mock AiClient ai;

    private AgentQuestionAnswerService service;

    @BeforeEach
    void setUp() {
        service = new AgentQuestionAnswerService(
                store,
                raw,
                governance,
                databaseAnalysis,
                emergingRoleService,
                evolutionService,
                ai,
                new ObjectMapper(),
                20000,
                18000,
                true,
                300000
        );
        when(store.insert(anyString(), any())).thenReturn(1L);
        when(store.list(argThat(sql -> sql != null && sql.contains("agent_chat_message")), any())).thenReturn(List.of());
        when(databaseAnalysis.analyze(anyString())).thenReturn(
                new AgentDatabaseAnalysisService.AnalysisResult(List.of(), List.of(), false)
        );
    }

    @Test
    void modelReceivesGovernedRawDataAsGroundingEvidence() {
        when(governance.analysisSnapshot()).thenReturn(Map.of(
                "readyForAnalysis", true,
                "snapshotVersion", 1200,
                "validRows", 1180
        ));
        when(raw.list(argThat(sql -> sql != null && sql.contains("FROM (SELECT raw_job_id")), any(Object[].class)))
                .thenReturn(List.of(new LinkedHashMap<>(Map.of(
                        "raw_job_id", 42L,
                        "title_standard", "Java开发工程师",
                        "company", "示例科技",
                        "description_clean", "负责Spring Boot服务开发"
                ))));
        when(raw.list(argThat(sql -> sql != null && sql.contains("GROUP_CONCAT")), any(Object[].class)))
                .thenReturn(List.of(Map.of("raw_job_id", 42L, "skills", "Java、Spring Boot、MySQL")));
        when(store.list(argThat(sql -> sql != null && sql.contains("FROM role_skill")), any()))
                .thenReturn(List.of(Map.of(
                        "role_name", "Java开发工程师",
                        "skill_name", "Spring Boot",
                        "evidence_count", 12
                )));
        when(ai.modelName()).thenReturn("qwen-plus");
        when(ai.complete(anyString(), anyString(), anyString(), anyInt(), anyDouble())).thenReturn(Optional.of(
                "Java岗位主要要求Spring Boot与MySQL。[证据2]"
        ));

        AgentAnswer result = service.answer("Java岗位需要哪些技能？", "session-1");

        assertEquals("Java岗位主要要求Spring Boot与MySQL。[证据2]", result.answer());
        assertTrue(result.evidence().stream().anyMatch(row -> "governed_job".equals(row.get("evidenceType"))));
        assertTrue(result.agents().stream().anyMatch(name -> name.contains("qwen-plus")));
        verify(ai).complete(
                argThat(prompt -> prompt.contains("唯一事实来源") || prompt.contains("系统提供的检索证据")),
                argThat(prompt -> prompt.contains("Java开发工程师") && prompt.contains("Spring Boot"))
                ,
                anyString(),
                anyInt(),
                anyDouble()
        );
    }

    @Test
    void disabledModelIsClearlyReportedAsRetrievalMode() {
        when(governance.analysisSnapshot()).thenThrow(new IllegalStateException("MySQL unavailable"));
        when(store.list(argThat(sql -> sql != null && sql.contains("FROM emerging_candidate")), any()))
                .thenReturn(List.of(Map.of("candidate_name", "大模型应用工程师", "confidence", 0.88)));
        when(ai.enabled()).thenReturn(false);
        when(ai.modelName()).thenReturn("qwen-plus");
        when(ai.complete(anyString(), anyString(), anyString(), anyInt(), anyDouble())).thenReturn(Optional.empty());

        AgentAnswer result = service.answer("当前有哪些新岗位？", "session-2");

        assertTrue(result.answer().startsWith("当前未启用大模型"));
        assertFalse(result.evidence().isEmpty());
        assertTrue(result.suggestedActions().stream().anyMatch(action -> action.contains("AI_API_KEY")));
    }

    @Test
    void emptyEmergingTableIsAutomaticallyGeneratedBeforeModelCall() {
        when(governance.analysisSnapshot()).thenReturn(Map.of(
                "readyForAnalysis", true,
                "snapshotVersion", 1809100
        ));
        when(store.list(argThat(sql -> sql != null && sql.contains("FROM emerging_candidate")), any()))
                .thenReturn(
                        List.of(),
                        List.of(),
                        List.of(Map.of(
                                "candidate_name", "智能体应用工程师",
                                "required_skills", "大模型、RAG",
                                "confidence", 0.91
                        ))
                );
        when(emergingRoleService.discover()).thenReturn(Map.of(
                "trainingYear", 2025,
                "targetYear", 2026,
                "candidates", 1
        ));
        when(ai.modelName()).thenReturn("qwen-plus");
        when(ai.complete(anyString(), anyString(), anyString(), anyInt(), anyDouble())).thenReturn(Optional.of(
                "当前发现智能体应用工程师。[证据3]"
        ));

        AgentAnswer result = service.answer("当前发现了哪些新岗位？", "session-auto-emerging");

        verify(emergingRoleService).discover();
        assertTrue(result.evidence().stream().anyMatch(
                row -> "automatic_analysis_run".equals(row.get("evidenceType"))
        ));
        assertTrue(result.evidence().stream().anyMatch(
                row -> "智能体应用工程师".equals(row.get("candidate_name"))
        ));
    }

    @Test
    void addedSkillQuestionTriggersEvolutionAnalysisInsteadOfRoleSkillLookup() {
        when(governance.analysisSnapshot()).thenReturn(Map.of(
                "readyForAnalysis", true,
                "snapshotVersion", 1809100
        ));
        when(store.list(argThat(sql -> sql != null && sql.contains("FROM evolution_event")), any()))
                .thenReturn(
                        List.of(),
                        List.of(),
                        List.of(Map.of(
                                "role_name", "Java开发工程师",
                                "skill_name", "云原生",
                                "change_type", "ADDED",
                                "evidence_count", 18
                        ))
                );
        when(evolutionService.analyze()).thenReturn(Map.of(
                "previousYear", 2025,
                "currentYear", 2026,
                "events", 1
        ));
        when(ai.modelName()).thenReturn("qwen-plus");
        when(ai.complete(anyString(), anyString(), anyString(), anyInt(), anyDouble())).thenReturn(Optional.of(
                "Java岗位新增了云原生技能信号。[证据3]"
        ));

        AgentAnswer result = service.answer(
                "Java 岗位最近新增了哪些技能？",
                "session-auto-evolution"
        );

        verify(evolutionService).analyze();
        assertTrue(result.evidence().stream().anyMatch(
                row -> "skill_evolution_analysis".equals(row.get("evidenceType"))
        ));
    }
}
