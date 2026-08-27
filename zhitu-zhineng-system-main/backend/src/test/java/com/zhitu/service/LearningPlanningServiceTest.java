package com.zhitu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhitu.ai.AiClient;
import com.zhitu.common.Jsons;
import com.zhitu.engine.LearningPathPlanner;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningPlanningServiceTest {

    @Mock Store store;
    @Mock LearningPathPlanner planner;
    @Mock AiClient ai;

    private LearningPlanningService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new LearningPlanningService(store, planner, new Jsons(mapper), ai, mapper);
    }

    @Test
    void modelPlanIsGroundedNormalizedAndReturnedAsDetailedStages() {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("person_name", "张晨");
        match.put("role_name", "智能体工程师");
        match.put("tech_stack", "AI");
        match.put("level_name", "初级");
        match.put("overall_score", 72);
        match.put("matched_skills", "[\"Python\",\"LangChain\"]");
        match.put("missing_skills", "[\"RAG评测\",\"LangGraph\"]");
        match.put("suggestions", "[\"补齐RAG评测\"]");
        match.put("resume_skills", "[\"Python\",\"FastAPI\"]");
        match.put("resume_projects", "[\"企业知识库问答系统\"]");
        match.put("education", "本科");
        match.put("experience_years", 0.5);
        when(store.one(argThat(sql -> sql != null && sql.contains("FROM match_report")), any())).thenReturn(match);

        Map<String, Object> fallbackStage = new LinkedHashMap<>();
        fallbackStage.put("phase", 1);
        fallbackStage.put("weekRange", "第1-4周");
        fallbackStage.put("hours", 32);
        fallbackStage.put("skill", "RAG评测");
        fallbackStage.put("theme", "评测专项");
        fallbackStage.put("goal", "建立评测闭环");
        fallbackStage.put("rationale", "岗位关键缺口");
        fallbackStage.put("dependency", "RAG基础");
        fallbackStage.put("topics", List.of("检索指标"));
        fallbackStage.put("weeklyTasks", List.of("构建数据集"));
        fallbackStage.put("deliverables", List.of("评测报告"));
        fallbackStage.put("assessment", List.of("指标达标"));
        fallbackStage.put("successCriteria", List.of("可复现"));
        fallbackStage.put("resources", List.of("官方文档"));
        List<Map<String, Object>> fallback = List.of(fallbackStage);
        when(planner.plan(any(), any(Integer.class), any(Integer.class))).thenReturn(fallback);
        when(ai.enabled()).thenReturn(true);
        when(ai.modelName()).thenReturn("qwen-plus");
        when(ai.complete(anyString(), anyString())).thenReturn(Optional.of("""
                {"title":"智能体工程师12周进阶计划","objective":"补齐评测和编排能力", "strategy":["先评测后编排","项目验收"],"steps":[{
                  "phase":1,"weekRange":"第1-4周","hours":32,"skill":"RAG评测","theme":"建立质量闭环",
                  "goal":"能够独立完成RAG离线评测","rationale":"简历已有知识库项目但缺少量化评测证据",
                  "topics":["Recall@K","答案忠实度"],"weeklyTasks":["建立50条评测集","实现自动评测脚本"],
                  "deliverables":["评测数据集","评测报告"],"assessment":["运行盲测"],
                  "successCriteria":["检索指标可复现"],"resources":["框架官方文档"],"dependency":"RAG基础"
                }]}
                """));

        AtomicReference<String> storedSteps = new AtomicReference<>();
        when(store.insert(argThat(sql -> sql != null && sql.contains("learning_path")), any())).thenAnswer(invocation -> {
            Map<String, ?> params = invocation.getArgument(1);
            storedSteps.set(String.valueOf(params.get("s")));
            return 66L;
        });
        when(store.one(argThat(sql -> sql != null && sql.startsWith("SELECT * FROM learning_path")), any())).thenAnswer(invocation -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", 66L);
            row.put("title", "智能体工程师12周进阶计划");
            row.put("objective", "补齐评测和编排能力");
            row.put("weeks", 12);
            row.put("steps_json", storedSteps.get());
            return row;
        });

        Map<String, Object> result = service.generate(9L, 12, 8);

        assertEquals("AI_DEEP_PLAN", result.get("plannerMode"));
        assertEquals("qwen-plus", result.get("modelName"));
        List<?> steps = (List<?>) result.get("steps");
        assertFalse(steps.isEmpty());
        Map<?, ?> first = (Map<?, ?>) steps.get(0);
        assertTrue(((List<?>) first.get("weeklyTasks")).size() >= 2);
        assertTrue(((List<?>) first.get("deliverables")).contains("评测报告"));
        verify(ai).complete(
                argThat(prompt -> prompt.contains("动态规划") && prompt.contains("仅输出")),
                argThat(input -> input.contains("张晨") && input.contains("企业知识库问答系统"))
        );
    }
}
