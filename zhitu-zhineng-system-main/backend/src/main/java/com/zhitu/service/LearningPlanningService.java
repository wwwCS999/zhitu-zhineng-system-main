package com.zhitu.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhitu.ai.AiClient;
import com.zhitu.common.Jsons;
import com.zhitu.common.TextUtils;
import com.zhitu.engine.LearningPathPlanner;
import com.zhitu.repository.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class LearningPlanningService {

    private static final String SYSTEM_PROMPT = """
            你是企业人才发展与岗位胜任力动态规划专家。请根据真实的人岗匹配报告生成阶段制、可执行、可验收、可复盘的培养方案。
            必须遵守：
            1. 只围绕输入中的岗位、已掌握技能、技能缺口、项目经历、教育背景和企业培养策略规划，不得虚构候选人经历；
            2. 先修能力在前，岗位核心缺口居中，端到端岗位项目靠后，并按岗位晋升机制形成可逐层解锁的能力金字塔；
            3. 根据 planningModes 组合动态调整培养侧重点：SKILL_GAP 侧重技能短板补齐，ONBOARDING 侧重上岗规范和试用期交付，PROMOTION 侧重复杂项目和业务影响，PORTFOLIO 侧重作品集证据；
            4. 输出必须体现企业培训管理视角：阶段目标、业务场景、训练动作、交付物、验收标准、成功指标、解锁规则和导师复核口径；
            5. 任务必须具体到可执行动作，交付物必须能够放入作品集、试用期复盘或面试证明；
            6. weeks 和 hoursPerWeek 只是内部资源估算，不要把周期限制当成用户前置配置；根据岗位难度拆成 4-7 个阶段，每阶段用 weekRange 描述企业培养节奏；
            7. 不要输出 Markdown，不要输出 JSON 之外的说明。

            仅输出以下 JSON 结构：
            {"title":"...","objective":"...","strategy":["..."],"steps":[{"phase":1,"weekRange":"第1-2周","hours":16,"skill":"...","pyramidTier":"第一层：岗位基础","theme":"...","goal":"...","rationale":"...","checkpoint":"...","unlockRule":"完成本层验收后解锁下一层","topics":["..."],"weeklyTasks":["..."],"deliverables":["..."],"assessment":["..."],"successCriteria":["..."],"kpi":["..."],"resources":["..."],"dependency":"..."}]}
            """;

    private static final String OPTIMIZE_PROMPT = """
            你是企业人才发展与岗位胜任力方案架构师。请基于已有学习路径做二次校准，输出更适合企业落地的阶段制数字化培养方案。
            必须遵守：
            1. 不得虚构候选人经历、教育背景、项目或已掌握技能，只能围绕输入材料重排训练路径；
            2. 保留目标岗位、候选人画像和企业策略，weeks 与 hoursPerWeek 仅作为资源估算，不作为页面侧强制限制；
            3. 优先把长段文字压缩成阶段目标、可执行任务、可交付证据、验收标准、量化 KPI 和金字塔解锁规则；
            4. 每阶段必须能被企业导师验收，交付物要能进入作品集、试用期复盘、晋升评审或面试证据；
            5. 根据 planningModes 组合强化培养目的：缺口补齐、试用转正、晋升储备、作品集强化可以同时生效；
            6. 不要输出 Markdown，不要输出 JSON 之外的说明。

            仅输出以下 JSON 结构：
            {"title":"...","objective":"...","strategy":["..."],"steps":[{"phase":1,"weekRange":"第1-2周","hours":16,"skill":"...","theme":"...","priorityLevel":"P0","businessScenario":"...","goal":"...","rationale":"...","checkpoint":"...","topics":["..."],"weeklyTasks":["..."],"deliverables":["..."],"assessment":["..."],"successCriteria":["..."],"kpi":["..."],"resources":["..."],"dependency":"..."}]}
            """;

    private final Store store;
    private final LearningPathPlanner planner;
    private final Jsons jsons;
    private final AiClient ai;
    private final ObjectMapper mapper;

    @Value("${app.ai.learning.model:${app.ai.model:deepseek-chat}}")
    private String learningAiModel = "deepseek-chat";

    public LearningPlanningService(
            Store store,
            LearningPathPlanner planner,
            Jsons jsons,
            AiClient ai,
            ObjectMapper mapper
    ) {
        this.store = store;
        this.planner = planner;
        this.jsons = jsons;
        this.ai = ai;
        this.mapper = mapper;
    }

    public Map<String, Object> generate(long matchId, int requestedWeeks, int requestedHours) {
        return generate(matchId, requestedWeeks, requestedHours, "SKILL_GAP");
    }

    public Map<String, Object> generate(long matchId, int requestedWeeks, int requestedHours, String requestedPlanMode) {
        return generate(
                matchId,
                requestedWeeks,
                requestedHours,
                requestedPlanMode == null ? List.of() : List.of(requestedPlanMode),
                requestedPlanMode
        );
    }

    public Map<String, Object> generate(
            long matchId,
            int requestedWeeks,
            int requestedHours,
            List<String> requestedPlanModes,
            String fallbackPlanMode
    ) {
        int weeks = Math.max(1, Math.min(52, requestedWeeks));
        int hours = Math.max(1, Math.min(40, requestedHours));
        List<String> planModes = normalizePlanModes(requestedPlanModes, fallbackPlanMode);
        String primaryPlanMode = planModes.get(0);
        Map<String, Object> match = store.one(
                "SELECT m.*,r.role_name,r.tech_stack,r.level_name,p.person_name,p.skills AS resume_skills," +
                        "p.projects AS resume_projects,p.education,p.experience_years " +
                        "FROM match_report m JOIN job_role r ON r.id=m.role_id " +
                        "JOIN resume_profile p ON p.id=m.resume_id WHERE m.id=:id",
                Map.of("id", matchId)
        );
        List<String> missing = TextUtils.jsonList(String.valueOf(match.get("missing_skills")));
        List<String> roadmapSeeds = roadmapSeeds(match, missing, planModes);
        List<Map<String, Object>> fallback = planner.plan(roadmapSeeds, weeks, hours);
        PlanDraft draft = generateWithModel(match, missing, weeks, hours, planModes, fallback)
                .orElseGet(() -> fallbackDraft(match, missing, weeks, planModes, fallback));

        List<Map<String, Object>> steps = stampedSteps(draft, primaryPlanMode, planModes);

        long id = store.insert(
                "INSERT INTO learning_path(match_id,title,weeks,objective,steps_json) VALUES(:m,:t,:w,:o,:s)",
                Map.of(
                        "m", matchId,
                        "t", draft.title(),
                        "w", weeks,
                        "o", draft.objective(),
                        "s", jsons.write(steps)
                )
        );
        return detail(id);
    }

    public Map<String, Object> optimize(long pathId) {
        Map<String, Object> path = store.one(
                "SELECT l.id AS path_id,l.match_id,l.title,l.weeks,l.objective,l.steps_json," +
                        "m.overall_score,m.matched_skills,m.missing_skills,m.suggestions," +
                        "r.role_name,r.tech_stack,r.level_name,p.person_name,p.skills AS resume_skills," +
                        "p.projects AS resume_projects,p.education,p.experience_years " +
                        "FROM learning_path l JOIN match_report m ON m.id=l.match_id " +
                        "JOIN job_role r ON r.id=m.role_id JOIN resume_profile p ON p.id=m.resume_id " +
                        "WHERE l.id=:id",
                Map.of("id", pathId)
        );
        List<Map<String, Object>> current = jsons.listOfMaps(String.valueOf(path.get("steps_json")));
        int weeks = Math.max(1, Math.min(52, number(path.get("weeks"), 12)));
        int generatedHours = totalStepHours(current);
        int hoursPerWeek = generatedHours > 0
                ? Math.max(1, Math.min(40, (int) Math.ceil(generatedHours / (double) weeks)))
                : 8;
        List<String> missing = TextUtils.jsonList(String.valueOf(path.get("missing_skills")));
        List<String> planModes = planModesFromSteps(current);
        if (current.isEmpty() || current.size() < 4) {
            current = planner.plan(roadmapSeeds(path, missing, planModes), weeks, hoursPerWeek);
        }
        final List<Map<String, Object>> currentSteps = current;

        PlanDraft draft = optimizeWithModel(path, missing, weeks, hoursPerWeek, planModes, currentSteps)
                .orElseGet(() -> optimizedFallbackDraft(path, missing, weeks, planModes, currentSteps));
        List<Map<String, Object>> steps = stampedSteps(draft, planModes.get(0), planModes);
        store.update(
                "UPDATE learning_path SET title=:t,objective=:o,steps_json=:s WHERE id=:id",
                Map.of(
                        "id", pathId,
                        "t", draft.title(),
                        "o", draft.objective(),
                        "s", jsons.write(steps)
                )
        );
        return detail(pathId);
    }

    private Optional<PlanDraft> generateWithModel(
            Map<String, Object> match,
            List<String> missing,
            int weeks,
            int hours,
            List<String> planModes,
            List<Map<String, Object>> fallback
    ) {
        if (!ai.enabled()) return Optional.empty();
        String primaryPlanMode = planModes.get(0);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("candidate", match.get("person_name"));
        input.put("targetRole", match.get("role_name"));
        input.put("targetStack", match.get("tech_stack"));
        input.put("targetLevel", match.get("level_name"));
        input.put("overallScore", match.get("overall_score"));
        input.put("matchedSkills", TextUtils.jsonList(String.valueOf(match.get("matched_skills"))));
        input.put("missingSkills", missing);
        input.put("suggestions", TextUtils.jsonList(String.valueOf(match.get("suggestions"))));
        input.put("resumeSkills", TextUtils.jsonList(String.valueOf(match.get("resume_skills"))));
        input.put("resumeProjects", TextUtils.jsonList(String.valueOf(match.get("resume_projects"))));
        input.put("education", match.get("education"));
        input.put("experienceYears", match.get("experience_years"));
        input.put("planningMode", primaryPlanMode);
        input.put("planningModes", planModes);
        input.put("planningModeLabel", planModeLabel(primaryPlanMode));
        input.put("planningModeLabels", planModeLabels(planModes));
        input.put("weeks", weeks);
        input.put("hoursPerWeek", hours);
        input.put("deterministicPrerequisitePlan", fallback);

        String model = learningModel();
        Optional<String> response = ai.complete(SYSTEM_PROMPT, jsons.write(input), model, 4096, 0.1D);
        if (response.isEmpty()) return Optional.empty();
        try {
            JsonNode root = mapper.readTree(stripCodeFence(response.get()));
            JsonNode stepNodes = root.path("steps");
            if (!stepNodes.isArray() || stepNodes.isEmpty()) return Optional.empty();
            List<Map<String, Object>> steps = mapper.convertValue(stepNodes, new TypeReference<>() {});
            List<Map<String, Object>> normalized = normalizeModelSteps(steps, fallback, weeks, hours);
            if (normalized.isEmpty()) return Optional.empty();
            String title = text(root.get("title"), "面向“" + match.get("role_name") + "”的阶段制岗位培养方案");
            String objective = text(root.get("objective"), objective(match, missing, planModes));
            List<String> strategy = stringList(root.get("strategy"));
            if (strategy.isEmpty()) strategy = defaultStrategy(missing, planModes);
            return Optional.of(new PlanDraft(title, objective, strategy, normalized, "AI_DEEP_PLAN",
                    ai.lastSuccessfulModel().orElse(model)));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<PlanDraft> optimizeWithModel(
            Map<String, Object> path,
            List<String> missing,
            int weeks,
            int hours,
            List<String> planModes,
            List<Map<String, Object>> current
    ) {
        if (!ai.enabled()) return Optional.empty();
        Map<String, Object> currentPlan = new LinkedHashMap<>();
        currentPlan.put("title", path.get("title"));
        currentPlan.put("objective", path.get("objective"));
        currentPlan.put("steps", current);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("candidate", path.get("person_name"));
        input.put("targetRole", path.get("role_name"));
        input.put("targetStack", path.get("tech_stack"));
        input.put("targetLevel", path.get("level_name"));
        input.put("overallScore", path.get("overall_score"));
        input.put("matchedSkills", TextUtils.jsonList(String.valueOf(path.get("matched_skills"))));
        input.put("missingSkills", missing);
        input.put("suggestions", TextUtils.jsonList(String.valueOf(path.get("suggestions"))));
        input.put("resumeSkills", TextUtils.jsonList(String.valueOf(path.get("resume_skills"))));
        input.put("resumeProjects", TextUtils.jsonList(String.valueOf(path.get("resume_projects"))));
        input.put("education", path.get("education"));
        input.put("experienceYears", path.get("experience_years"));
        input.put("planningModes", planModes);
        input.put("planningModeLabels", planModeLabels(planModes));
        input.put("weeks", weeks);
        input.put("hoursPerWeek", hours);
        input.put("currentPlan", currentPlan);

        String model = learningModel();
        Optional<String> response = ai.complete(OPTIMIZE_PROMPT, jsons.write(input), model, 4096, 0.1D);
        if (response.isEmpty()) return Optional.empty();
        try {
            JsonNode root = mapper.readTree(stripCodeFence(response.get()));
            JsonNode stepNodes = root.path("steps");
            if (!stepNodes.isArray() || stepNodes.isEmpty()) return Optional.empty();
            List<Map<String, Object>> steps = mapper.convertValue(stepNodes, new TypeReference<>() {});
            List<Map<String, Object>> normalized = normalizeModelSteps(steps, current, weeks, hours);
            if (normalized.isEmpty()) return Optional.empty();
            String title = text(root.get("title"), "面向“" + path.get("role_name") + "”的阶段制数字化培养方案");
            String objective = text(root.get("objective"), objective(path, missing, planModes));
            List<String> strategy = stringList(root.get("strategy"));
            if (strategy.isEmpty()) strategy = defaultStrategy(missing, planModes);
            return Optional.of(new PlanDraft(title, objective, strategy, normalized, "AI_OPTIMIZED_PLAN",
                    ai.lastSuccessfulModel().orElse(model)));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private List<Map<String, Object>> normalizeModelSteps(
            List<Map<String, Object>> modelSteps,
            List<Map<String, Object>> fallback,
            int weeks,
            int hoursPerWeek
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        int limit = Math.min(12, modelSteps.size());
        for (int index = 0; index < limit; index++) {
            Map<String, Object> source = modelSteps.get(index);
            Map<String, Object> defaults = fallback.isEmpty() ? new LinkedHashMap<>() : fallback.get(Math.min(index, fallback.size() - 1));
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("phase", index + 1);
            for (String key : List.of("weekRange", "skill", "theme", "pyramidTier", "priorityLevel", "businessScenario", "goal", "rationale", "checkpoint", "unlockRule", "dependency")) {
                String value = text(source.get(key), String.valueOf(defaults.getOrDefault(key, "")));
                step.put(key, value);
            }
            int defaultHours = number(defaults.get("hours"), hoursPerWeek);
            step.put("hours", Math.max(1, Math.min(weeks * hoursPerWeek, number(source.get("hours"), defaultHours))));
            for (String key : List.of("topics", "weeklyTasks", "deliverables", "assessment", "successCriteria", "kpi", "resources")) {
                List<String> values = stringList(source.get(key));
                if (values.isEmpty()) values = stringList(defaults.get(key));
                step.put(key, values.stream().limit(8).toList());
            }
            result.add(step);
        }
        return result;
    }

    private String learningModel() {
        return learningAiModel == null || learningAiModel.isBlank()
                ? ai.modelName()
                : learningAiModel.trim();
    }

    private PlanDraft optimizedFallbackDraft(
            Map<String, Object> match,
            List<String> missing,
            int weeks,
            List<String> planModes,
            List<Map<String, Object>> current
    ) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int index = 0; index < current.size(); index++) {
            Map<String, Object> source = current.get(index);
            Map<String, Object> step = new LinkedHashMap<>(source);
            String skill = text(step.get("skill"), "岗位综合能力");
            step.put("phase", index + 1);
            step.put("pyramidTier", text(step.get("pyramidTier"), pyramidTier(index + 1, current.size())));
            step.put("priorityLevel", text(step.get("priorityLevel"), index == 0 && !missing.isEmpty() ? "P0" : "P1"));
            step.put("businessScenario", text(step.get("businessScenario"), defaultBusinessScenario(skill, planModes)));
            step.put("checkpoint", text(step.get("checkpoint"), "完成“" + skill + "”阶段交付物，并通过导师验收记录"));
            step.put("unlockRule", text(step.get("unlockRule"), index == 0 ? "默认解锁，完成本层任务后进入下一层" : "上一层验收通过后解锁"));
            step.put("topics", limitedList(stringList(step.get("topics")), List.of(skill + "核心知识", "岗位应用场景", "常见问题与复盘")));
            step.put("weeklyTasks", limitedList(stringList(step.get("weeklyTasks")), List.of("拆解岗位任务并完成最小闭环", "沉淀代码、文档或分析证据", "完成阶段复盘并更新能力清单")));
            List<String> deliverables = stringList(step.get("deliverables"));
            if (deliverables.isEmpty()) deliverables = stringList(step.get("deliverable"));
            step.put("deliverables", limitedList(deliverables, List.of(skill + "专项作品", "阶段复盘文档", "可验证证据清单")));
            step.put("assessment", limitedList(stringList(step.get("assessment")), List.of("导师验收通过", "关键指标达到80分以上", "交付物可复现")));
            step.put("successCriteria", limitedList(stringList(step.get("successCriteria")), List.of("完成全部必做任务", "产出至少2类证据", "能解释岗位场景中的技术取舍")));
            step.put("kpi", limitedList(stringList(step.get("kpi")), defaultKpis(skill)));
            step.put("resources", limitedList(stringList(step.get("resources")), List.of("岗位JD技能证据", "企业项目样例", "官方文档与工程案例")));
            steps.add(step);
        }
        List<String> strategy = new ArrayList<>(defaultStrategy(missing, planModes));
        strategy.add(0, "将学习路径拆解为阶段任务、交付证据、验收标准和量化KPI，便于企业导师跟踪");
        return new PlanDraft(
                "面向“" + match.get("role_name") + "”的阶段制数字化培养方案",
                objective(match, missing, planModes) + " 系统已将方案重排为可点击阶段、可度量任务、可复核交付物和企业验收口径。",
                strategy,
                steps,
                "RULE_BASED_OPTIMIZED_PLAN",
                "deterministic-fallback"
        );
    }

    private List<Map<String, Object>> stampedSteps(PlanDraft draft, String primaryPlanMode, List<String> planModes) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Map<String, Object> source : draft.steps()) {
            steps.add(new LinkedHashMap<>(source));
        }
        if (!steps.isEmpty()) {
            steps.get(0).put("plannerMode", draft.mode());
            steps.get(0).put("modelName", draft.modelName());
            steps.get(0).put("planStrategy", draft.strategy());
            steps.get(0).put("planMode", primaryPlanMode);
            steps.get(0).put("planModes", planModes);
        }
        return steps;
    }

    private PlanDraft fallbackDraft(Map<String, Object> match, List<String> missing, int weeks, List<String> planModes, List<Map<String, Object>> steps) {
        return new PlanDraft(
                "面向“" + match.get("role_name") + "”的阶段制岗位培养方案",
                objective(match, missing, planModes),
                defaultStrategy(missing, planModes),
                steps,
                "RULE_BASED_DETAILED_PLAN",
                "deterministic-fallback"
        );
    }

    private String objective(Map<String, Object> match, List<String> missing, List<String> planModes) {
        String focus = missing.isEmpty()
                ? "岗位基础、专项能力、协作交付和作品化验收"
                : String.join("、", missing.stream().limit(5).toList());
        return "针对“" + match.get("person_name") + "”与“" + match.get("role_name") + "”的匹配结果，按“" + String.join("、", planModeLabels(planModes)) + "”组合策略构建" + focus +
                "的晋升式能力金字塔，通过逐层解锁、阶段考核和端到端项目形成可验证的岗位作品集。";
    }

    private List<String> defaultStrategy(List<String> missing, List<String> planModes) {
        List<String> strategy = new ArrayList<>();
        String focus = missing.isEmpty() ? "岗位能力金字塔" : String.join("、", missing.stream().limit(3).toList());
        strategy.add("按照岗位晋升机制拆分为基础层、核心层、进阶层、协作层和作品层");
        if (planModes.contains("SKILL_GAP")) strategy.add("按照技能前置依赖安排学习顺序，优先补齐岗位硬性缺口");
        if (planModes.contains("ONBOARDING")) strategy.add("以试用期上岗为目标，覆盖企业规范、协作流程和稳定交付");
        if (planModes.contains("PROMOTION")) strategy.add("以晋升储备为目标，强化复杂项目、业务指标和方案表达");
        if (planModes.contains("PORTFOLIO")) strategy.add("以作品集证据为目标，把每阶段训练沉淀为可展示成果");
        strategy.addAll(List.of(
                "每个阶段同时覆盖知识、编码、岗位场景和复盘",
                "围绕“" + focus + "”形成专项证据",
                "最终用端到端项目验证综合岗位胜任力"
        ));
        return strategy;
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> path = store.one("SELECT * FROM learning_path WHERE id=:id", Map.of("id", id));
        List<Map<String, Object>> steps = jsons.listOfMaps(String.valueOf(path.get("steps_json")));
        path.put("steps", steps);
        if (!steps.isEmpty()) {
            Map<String, Object> first = steps.get(0);
            path.put("plannerMode", first.getOrDefault("plannerMode", "RULE_BASED_DETAILED_PLAN"));
            path.put("modelName", first.getOrDefault("modelName", "deterministic-fallback"));
            path.put("planMode", first.getOrDefault("planMode", "SKILL_GAP"));
            path.put("planModes", first.getOrDefault("planModes", List.of(first.getOrDefault("planMode", "SKILL_GAP"))));
            path.put("strategy", stringList(first.get("planStrategy")));
        }
        return path;
    }

    public List<Map<String, Object>> paths() {
        return store.list(
                "SELECT l.id,l.title,l.weeks,l.objective,l.created_at,m.overall_score " +
                        "FROM learning_path l JOIN match_report m ON m.id=l.match_id ORDER BY l.id DESC",
                Map.of()
        );
    }

    private static List<String> roadmapSeeds(Map<String, Object> match, List<String> missing, List<String> planModes) {
        LinkedHashSet<String> seeds = new LinkedHashSet<>();
        if (missing != null) {
            for (String skill : missing) {
                if (skill != null && !skill.isBlank()) seeds.add(skill.trim());
            }
        }

        String role = String.valueOf(match.getOrDefault("role_name", "")).toLowerCase(Locale.ROOT);
        String stack = String.valueOf(match.getOrDefault("tech_stack", "")).toLowerCase(Locale.ROOT);
        String combined = role + " " + stack;
        if (combined.contains("java") || combined.contains("后端")) {
            addAll(seeds, "Java", "Spring Boot", "MySQL", "Redis", "RESTful API", "Docker", "系统设计");
        } else if (combined.contains("agent") || combined.contains("智能体") || combined.contains("大模型")) {
            addAll(seeds, "Python", "Prompt Engineering", "RAG", "LangChain", "LangGraph", "AI Agent", "MCP");
        } else if (combined.contains("数据") || combined.contains("分析")) {
            addAll(seeds, "SQL", "Python", "Pandas", "数据可视化", "机器学习", "Tableau", "业务分析");
        } else if (combined.contains("前端") || combined.contains("vue") || combined.contains("react")) {
            addAll(seeds, "JavaScript", "TypeScript", "Vue.js", "React", "前端工程化", "接口联调");
        } else if (combined.contains("云") || combined.contains("devops") || combined.contains("运维")) {
            addAll(seeds, "Linux", "Docker", "Kubernetes", "CI/CD", "监控告警", "云原生部署");
        } else {
            addAll(seeds, "岗位基础规范", "核心工具链", "岗位专项能力", "业务场景交付", "复盘表达");
        }

        if (planModes.contains("ONBOARDING")) addAll(seeds, "企业协作规范");
        if (planModes.contains("PROMOTION")) addAll(seeds, "系统设计");
        if (planModes.contains("PORTFOLIO")) addAll(seeds, "作品集表达");
        return seeds.stream().limit(8).toList();
    }

    private static void addAll(LinkedHashSet<String> target, String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) target.add(value.trim());
        }
    }

    private static List<String> planModesFromSteps(List<Map<String, Object>> steps) {
        if (steps == null || steps.isEmpty()) return List.of("SKILL_GAP");
        Map<String, Object> first = steps.get(0);
        return normalizePlanModes(
                stringList(first.get("planModes")),
                String.valueOf(first.getOrDefault("planMode", "SKILL_GAP"))
        );
    }

    private static int totalStepHours(List<Map<String, Object>> steps) {
        if (steps == null) return 0;
        int total = 0;
        for (Map<String, Object> step : steps) {
            total += number(step.get("hours"), 0);
        }
        return total;
    }

    private static String defaultBusinessScenario(String skill, List<String> planModes) {
        String modes = String.join("、", planModeLabels(planModes));
        return "围绕“" + skill + "”在“" + modes + "”场景下完成企业可验收训练。";
    }

    private static List<String> defaultKpis(String skill) {
        return List.of(
                skill + "阶段任务完成率100%",
                "交付物可复现并可进入作品集",
                "导师验收或模拟面试评分不低于80分"
        );
    }

    private static String pyramidTier(int phase, int totalPhases) {
        if (phase == 1) return "第一层：岗位基础";
        if (phase == 2) return "第二层：核心技能";
        if (phase >= totalPhases) return "顶层：岗位作品";
        if (phase == totalPhases - 1) return "冲刺层：场景协作";
        return "进阶层：专项突破";
    }

    private static List<String> limitedList(List<String> values, List<String> fallback) {
        List<String> source = values == null || values.isEmpty() ? fallback : values;
        return source.stream()
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .limit(8)
                .toList();
    }

    private static String normalizePlanMode(String value) {
        if (value == null) return "SKILL_GAP";
        String mode = value.trim().toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "ONBOARDING", "PROMOTION", "PORTFOLIO", "SKILL_GAP" -> mode;
            default -> "SKILL_GAP";
        };
    }

    private static List<String> normalizePlanModes(List<String> values, String fallback) {
        List<String> result = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                String mode = normalizePlanMode(value);
                if (!result.contains(mode)) result.add(mode);
            }
        }
        if (result.isEmpty()) result.add(normalizePlanMode(fallback));
        return result;
    }

    private static String planModeLabel(String mode) {
        return switch (normalizePlanMode(mode)) {
            case "ONBOARDING" -> "试用转正";
            case "PROMOTION" -> "晋升储备";
            case "PORTFOLIO" -> "作品集强化";
            default -> "缺口补齐";
        };
    }

    private static List<String> planModeLabels(List<String> modes) {
        return modes.stream().map(LearningPlanningService::planModeLabel).toList();
    }

    private static String stripCodeFence(String value) {
        String result = value == null ? "" : value.trim();
        result = result.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        int start = result.indexOf('{');
        int end = result.lastIndexOf('}');
        return start >= 0 && end > start ? result.substring(start, end + 1) : result;
    }

    private static String text(Object value, String fallback) {
        if (value == null) return fallback;
        String result = value instanceof JsonNode node ? node.asText("") : String.valueOf(value);
        result = result.trim();
        return result.isBlank() ? fallback : result;
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }

    private static List<String> stringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof JsonNode node) {
            if (node.isArray()) {
                List<String> result = new ArrayList<>();
                node.forEach(item -> {
                    if (!item.asText("").isBlank()) result.add(item.asText().trim());
                });
                return result;
            }
            return node.asText("").isBlank() ? List.of() : List.of(node.asText().trim());
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isBlank()).toList();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) return List.of();
        return java.util.Arrays.stream(text.split("[；;\\n]"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private record PlanDraft(
            String title,
            String objective,
            List<String> strategy,
            List<Map<String, Object>> steps,
            String mode,
            String modelName
    ) {
    }
}
