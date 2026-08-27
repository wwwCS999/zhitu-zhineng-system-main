package com.zhitu;

import com.zhitu.repository.Store;
import com.zhitu.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "spring.data.redis.repositories.enabled=false"
})
class FullPipelineIntegrationTest {
    @Autowired Store store;
    @Autowired DataGovernanceService governance;
    @Autowired JobInsightService jobs;
    @Autowired EmergingRoleService emerging;
    @Autowired EvolutionService evolution;
    @Autowired GraphService graph;
    @Autowired DashboardService dashboard;
    @Autowired ResumeService resumes;
    @Autowired MatchingService matching;
    @Autowired LearningPlanningService learning;
    @Autowired TrustedAuditService audit;
    @Autowired AgentOrchestratorService agents;

    @Test
    void completeCompetitionWorkflowIsAvailable() {
        assertTrue(store.count("job_posting") >= 100, "测试集岗位记录必须不少于100条");
        assertTrue(store.count("source_document") >= 100, "重复JD应保留来源记录并标记，而不是直接删除");
        assertFalse(jobs.jobs(20).isEmpty());
        assertFalse(graph.roles().isEmpty());

        Map<String, Object> overview = dashboard.overview();
        assertNotNull(overview.get("metrics"));
        assertNotNull(overview.get("agents"));

        Map<String, Object> panorama = graph.panorama("", "", 100);
        assertFalse(((java.util.Collection<?>) panorama.get("nodes")).isEmpty());
        assertFalse(((java.util.List<?>) panorama.get("links")).isEmpty());

        assertFalse(emerging.candidates().isEmpty(), "应生成新岗位候选");
        assertFalse(evolution.events().isEmpty(), "应生成既有岗位能力演化事件");
        assertFalse(resumes.profiles().isEmpty(), "初始化应生成演示简历画像");
        assertFalse(matching.reports().isEmpty(), "初始化应生成人岗匹配报告");
        assertFalse(learning.paths().isEmpty(), "初始化应生成学习路径");

        assertFalse(agents.chat("发现了哪些新岗位？").evidence().isEmpty());
        assertFalse(agents.chat("Java 岗位新增了哪些技能？").agents().isEmpty());
        assertFalse(agents.chat("如何进行人岗匹配和学习规划？").suggestedActions().isEmpty());
    }

    @Test
    void duplicateEvidenceIsRetainedAndMarked() {
        String content = "负责企业知识库系统建设，要求掌握 Java、Spring Boot、MySQL、Redis 与 Docker。";
        Map<String, Object> first = governance.importText("单元测试来源|企业A|Java岗位", "JD", null, content);
        Map<String, Object> repeated = governance.importText("单元测试来源|企业A|Java岗位", "JD", null, content);
        Map<String, Object> copied = governance.importText("另一平台|企业B|Java岗位", "JD", null, content);

        assertEquals(false, first.get("alreadyImported"));
        assertEquals(true, repeated.get("alreadyImported"));
        assertEquals(true, copied.get("duplicate"));
        assertNotEquals(first.get("documentId"), copied.get("documentId"));
    }

    @Test
    void privateNetworkUrlIsBlockedBeforeFetching() {
        assertThrows(IllegalArgumentException.class,
                () -> governance.importUrl("http://127.0.0.1/internal", "WEB"));
    }

    @Test
    void fullOrchestratorCanRunRepeatedly() {
        Map<String, Object> result = agents.runFull();
        assertEquals("COMPLETED", result.get("status"));
        assertEquals(7, ((java.util.List<?>) result.get("steps")).size());
        assertFalse(audit.pending().isEmpty());
    }
}
