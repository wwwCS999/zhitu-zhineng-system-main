package com.zhitu.engine;

import com.zhitu.service.SkillOntologyService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class LearningPathPlanner {

    private final SkillOntologyService ontology;

    public LearningPathPlanner(SkillOntologyService ontology) {
        this.ontology = ontology;
    }

    public List<Map<String, Object>> plan(List<String> missing, int requestedWeeks, int requestedHoursPerWeek) {
        int weeks = Math.max(1, Math.min(52, requestedWeeks));
        int hoursPerWeek = Math.max(1, Math.min(40, requestedHoursPerWeek));
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String skill : missing) {
            for (String prerequisite : ontology.prerequisites(skill)) ordered.add(prerequisite);
            ordered.add(skill);
        }
        if (ordered.isEmpty()) {
            ordered.add("岗位基础规范");
            ordered.add("核心工具链");
            ordered.add("岗位专项能力");
            ordered.add("业务场景交付");
            ordered.add("复盘表达");
        }

        int targetStages = weeks >= 16 ? 7 : weeks >= 10 ? 6 : weeks >= 6 ? 5 : Math.max(2, weeks);
        int skillStageLimit = Math.max(1, Math.min(targetStages - (weeks > 1 ? 1 : 0), ordered.size()));
        List<String> skills = new ArrayList<>(ordered).stream().limit(skillStageLimit).toList();
        List<String> stages = new ArrayList<>(skills);
        if (weeks > 1) stages.add("岗位综合项目");

        int baseWeeks = Math.max(1, weeks / stages.size());
        int remainder = Math.max(0, weeks - baseWeeks * stages.size());
        List<Map<String, Object>> result = new ArrayList<>();
        int startWeek = 1;

        for (int index = 0; index < stages.size(); index++) {
            String skill = stages.get(index);
            int allocatedWeeks = baseWeeks + (index >= stages.size() - remainder ? 1 : 0);
            int endWeek = index == stages.size() - 1 ? weeks : Math.min(weeks, startWeek + allocatedWeeks - 1);
            boolean projectStage = "岗位综合项目".equals(skill);
            result.add(stage(
                    index + 1,
                    stages.size(),
                    skill,
                    startWeek,
                    endWeek,
                    Math.max(hoursPerWeek, (endWeek - startWeek + 1) * hoursPerWeek),
                    projectStage,
                    missing
            ));
            startWeek = endWeek + 1;
            if (startWeek > weeks) break;
        }
        return result;
    }

    private Map<String, Object> stage(
            int phase,
            int totalPhases,
            String skill,
            int startWeek,
            int endWeek,
            int hours,
            boolean projectStage,
            List<String> missing
    ) {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("phase", phase);
        stage.put("weekRange", startWeek == endWeek ? "第" + startWeek + "周" : "第" + startWeek + "-" + endWeek + "周");
        stage.put("hours", hours);
        stage.put("skill", skill);
        stage.put("pyramidTier", pyramidTier(phase, totalPhases));
        stage.put("priorityLevel", phase <= 2 ? "P0" : projectStage ? "P2" : "P1");
        stage.put("theme", projectStage ? "岗位场景综合实战" : pyramidTier(phase, totalPhases) + " · " + skill);
        stage.put("businessScenario", projectStage
                ? "围绕目标岗位真实任务完成端到端项目验收。"
                : "完成“" + skill + "”在目标岗位中的可复用能力单元。");
        stage.put("goal", projectStage
                ? "把已补齐的能力整合为一个可运行、可演示、可复盘的岗位作品"
                : "从概念理解推进到独立实现，并形成能够证明掌握程度的工程证据");
        stage.put("rationale", projectStage
                ? "单项练习不能完全证明岗位胜任力，最终需要通过端到端项目串联技能、工程与业务场景。"
                : rationale(skill));
        stage.put("topics", projectStage
                ? List.of("需求与验收指标", "架构和数据流", "关键模块实现", "测试评测", "部署与复盘")
                : List.of(skill + "核心概念与边界", skill + "常用配置与工程规范", "典型岗位场景", "故障排查与性能优化"));
        stage.put("weeklyTasks", projectStage
                ? List.of("确定一个与目标岗位一致的真实问题和验收指标", "完成架构设计与迭代拆分", "实现核心链路并补充测试", "完成部署、演示文档与量化复盘")
                : List.of("阅读官方文档并输出知识地图", "完成最小可运行示例", "在目标岗位场景中完成专项练习", "整理错误案例、性能数据与复盘记录"));
        stage.put("deliverables", projectStage
                ? List.of("可运行项目仓库", "架构说明与部署文档", "测试或评测报告", "3-5分钟项目演示")
                : List.of(skill + "知识地图", skill + "专项代码或实验", "问题排查清单", "学习复盘记录"));
        stage.put("assessment", projectStage
                ? List.of("核心功能通过验收用例", "关键指标有量化结果", "能够解释技术取舍", "代码与文档可复现")
                : List.of("知识测验达到80分", "专项任务独立完成", "代码评审无阻断问题", "能用岗位案例解释原理"));
        stage.put("successCriteria", projectStage
                ? List.of("覆盖至少3项关键技能缺口", "形成可投递的作品证据", "能够进行完整项目讲解")
                : List.of("完成全部必做任务", "至少产出2类证据", "薄弱点复测达到80分"));
        stage.put("kpi", projectStage
                ? List.of("项目验收用例通过率100%", "演示材料完整可复盘", "岗位胜任力复评达到80分以上")
                : List.of(skill + "任务完成率100%", "阶段测评达到80分以上", "至少沉淀2类可复核证据"));
        stage.put("checkpoint", projectStage
                ? "完成项目仓库、部署文档、演示材料和量化复盘"
                : "完成“" + skill + "”阶段任务并通过导师/系统验收");
        stage.put("unlockRule", phase == 1
                ? "默认解锁，完成本层任务后进入下一层"
                : "上一层验收通过后解锁");
        stage.put("resources", List.of("官方文档与权威教程", "当前岗位JD技能证据", "开源项目和工程案例", "代码评审与模拟面试"));
        stage.put("dependency", projectStage ? String.join("、", missing.stream().limit(5).toList()) : dependency(skill));
        return stage;
    }

    private String rationale(String skill) {
        SkillOntologyService.Def definition = ontology.def(skill);
        if (definition == null) return skill + "属于当前匹配报告中的关键缺口，直接影响岗位核心任务完成质量。";
        return skill + "属于“" + definition.category() + "”能力，是目标岗位技能链路中的重要节点。";
    }

    private String dependency(String skill) {
        List<String> prerequisites = ontology.prerequisites(skill);
        return prerequisites.isEmpty() ? "无强制前置能力" : "建议先掌握：" + String.join("、", prerequisites);
    }

    private String pyramidTier(int phase, int totalPhases) {
        if (phase == 1) return "第一层：岗位基础";
        if (phase == 2) return "第二层：核心技能";
        if (phase == totalPhases) return "顶层：岗位作品";
        if (phase == totalPhases - 1) return "冲刺层：场景协作";
        return "进阶层：专项突破";
    }
}
