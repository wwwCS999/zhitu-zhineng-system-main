package com.zhitu.service;

import com.zhitu.repository.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmergingRoleServiceTest {

    private EmergingRoleService service;

    @BeforeEach
    void setUp() {
        service = new EmergingRoleService(null, null, null, null, null);
    }

    @Test
    void removesRecruitmentBenefitsAndFormatsSpecialization() {
        assertEquals(
                "风电运维工程师",
                service.cleanRoleTitle("风电运维工程师 入职六险一金 无实习")
        );
        assertEquals(
                "数据库架构师（基础设施方向）",
                service.cleanRoleTitle("数据库架构师 -基础设施")
        );
    }

    @Test
    void rejectsCompositeAndGenericLowValueTitles() {
        assertFalse(service.isPlausibleRoleTitle("居家客服、电销、审核、标注员"));
        assertFalse(service.isPlausibleRoleTitle("标注员"));
        assertTrue(service.isPlausibleRoleTitle("风电运维工程师"));
        assertTrue(service.isPlausibleRoleTitle("智能体应用工程师"));
    }

    @Test
    void stripsNestedNumbersAndSectionLabelsFromResponsibilities() {
        assertEquals(
                "负责数据库新技术研究并结合业务项目落地",
                service.cleanupSentence("、3、 3、岗位职责：负责数据库新技术研究并结合业务项目落地")
        );
        assertEquals(
                "负责风力发电机组的日常巡检、维护、故障消缺和检修",
                service.cleanupSentence("5.岗位职责： 负责风力发电机组的日常巡检、维护、故障消缺和检修")
        );
    }

    @Test
    void separatesCoreWorkFromCertificatesAndCandidateRequirements() {
        assertTrue(service.isResponsibility("负责数据库物理部署方案和应用架构设计方案落地"));
        assertFalse(service.isResponsibility("3)需持有高压电工证、低压电工证和高处维护作业证"));
        assertFalse(service.isResponsibility("具备全局视角，能够发现问题并推动业务项目"));
    }

    @Test
    void cleansExistingStoredCandidatesBeforeReturningThemToFrontend() {
        Store store = mock(Store.class);
        Map<String, Object> wind = new LinkedHashMap<>();
        wind.put("candidate_name", "风电运维工程师 入职六险一金 无实习");
        wind.put("cluster_key", "风电运维工程师 入职六险一金 无实习");
        wind.put("responsibilities", "[\"5.岗位职责：负责风力发电机组的日常巡检、维护和检修\",\"3)需持有高压电工证和维护作业证\"]");
        wind.put("scenarios", "[\"新能源行业 · 风电场设备运维\"]");
        wind.put("training_year", 2025);
        wind.put("target_year", 2026);

        Map<String, Object> composite = new LinkedHashMap<>(wind);
        composite.put("candidate_name", "居家客服、电销、审核、标注员");
        when(store.list(anyString(), any())).thenReturn(List.of(wind, composite));
        service = new EmergingRoleService(store, null, null, null, null);

        List<Map<String, Object>> result = service.candidates(2026);

        assertEquals(1, result.size());
        assertEquals("风电运维工程师", result.get(0).get("candidate_name"));
        assertFalse(String.valueOf(result.get(0).get("definition")).contains("5."));
        assertFalse(String.valueOf(result.get(0).get("responsibilities")).contains("高压电工证"));
    }
}
