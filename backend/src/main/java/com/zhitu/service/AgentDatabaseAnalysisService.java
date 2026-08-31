package com.zhitu.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhitu.ai.AiClient;
import com.zhitu.repository.RawDatabaseClient;
import com.zhitu.repository.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts open-ended questions into a small, auditable database analysis plan.
 *
 * The model never supplies executable SQL. It may only select from the query
 * types below and provide parameter values. SQL identifiers, joins, limits and
 * predicates remain server-owned and all user/model values are JDBC parameters.
 */
@Service
public class AgentDatabaseAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AgentDatabaseAnalysisService.class);
    private static final Pattern YEAR_PATTERN = Pattern.compile("(?<!\\d)(20\\d{2}|\\d{2})[年级]?");
    private static final int MAX_PLANS = 3;
    private static final int MAX_TOTAL_EVIDENCE = 12;

    private static final String PLANNER_PROMPT = """
            你是岗位数据库查询规划器。请把用户问题转换为安全的结构化查询计划，不要回答问题，不要输出 SQL。
            只能使用以下 queryType：
            - TOP_ROLES：按真实 JD 数量统计热门岗位；支持 year、city、roleKeyword、limit。
            - ROLE_TREND：岗位逐年数量趋势；支持 roleKeyword、startYear、endYear、city。
            - TOP_SKILLS：技能需求量排行；支持 year、roleKeyword、limit。
            - TOP_CITIES：岗位城市分布；支持 year、roleKeyword、limit。
            - TOP_COMPANIES：招聘企业排行；支持 year、roleKeyword、city、limit。
            - TOP_INDUSTRIES：招聘行业分布；支持 year、roleKeyword、city、limit。
            - EDUCATION_DISTRIBUTION：学历要求分布；支持 year、roleKeyword、city、limit。
            - TECH_STACK_DISTRIBUTION：技术栈分布；支持 year、roleKeyword、city、limit。
            - MARKET_SUMMARY：符合筛选条件的岗位总量、岗位种类、企业数和平均薪资。
            - SALARY_SUMMARY：薪资统计；支持 year、roleKeyword、city。
            - JOB_SAMPLES：查询真实岗位样本；支持 year、roleKeyword、city、limit。
            - EMERGING_CANDIDATES：系统已经计算的新岗位候选。
            - EVOLUTION_EVENTS：最近两年岗位技能新增、修改或弱化事件；支持 roleKeyword。
            - MATCH_REPORTS：简历与岗位匹配、技能缺口。
            - LEARNING_CONTEXT：学习路径、技能缺口和系统生成规则。
            - SYSTEM_OVERVIEW：治理数据规模与状态。

            规则：
            1. “25年/25级”按 2025 年理解；其他两位年份同理。
            2. “热门/最多/需求量最大”使用 TOP_ROLES，不能使用新岗位候选代替。
            3. “根据某年热门岗位预测下一年”至少查询 TOP_ROLES；数据库结果会包含上下半年数量，可用于趋势推断。
            4. “新增了哪些技能”使用 EVOLUTION_EVENTS；“需要哪些技能”使用 TOP_SKILLS。
            5. “如何生成学习路径”使用 LEARNING_CONTEXT。
            6. 最多输出 3 个查询，limit 为 1~10。

            仅输出 JSON：
            {"queries":[{"queryType":"TOP_ROLES","year":2025,"startYear":null,"endYear":null,"roleKeyword":"","city":"","limit":10}],"reason":"..."}
            """;

    private final RawDatabaseClient raw;
    private final Store store;
    private final RawJobGovernanceService governance;
    private final AiClient ai;
    private final ObjectMapper mapper;

    public AgentDatabaseAnalysisService(
            RawDatabaseClient raw,
            Store store,
            RawJobGovernanceService governance,
            AiClient ai,
            ObjectMapper mapper
    ) {
        this.raw = raw;
        this.store = store;
        this.governance = governance;
        this.ai = ai;
        this.mapper = mapper;
    }

    public AnalysisResult analyze(String question) {
        List<String> warnings = new ArrayList<>();
        List<QueryPlan> plans = deterministicPlans(question);
        boolean plannedByModel = false;

        if (plans.isEmpty() && ai.enabled()) {
            Optional<String> planned = ai.complete(PLANNER_PROMPT, "用户问题：" + question);
            if (planned.isPresent()) {
                plans = parsePlans(planned.get(), warnings);
                plannedByModel = !plans.isEmpty();
            } else {
                warnings.add("大模型未能生成数据库分析计划，已回退到常规证据检索");
            }
        }

        if (plans.isEmpty()) {
            return new AnalysisResult(List.of(), warnings, plannedByModel);
        }

        List<Map<String, Object>> evidence = new ArrayList<>();
        for (QueryPlan plan : plans.stream().limit(MAX_PLANS).toList()) {
            try {
                List<Map<String, Object>> rows = execute(plan);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("evidenceType", "database_analysis_plan");
                meta.put("source", sourceFor(plan.type()));
                meta.put("queryType", plan.type().name());
                meta.put("year", plan.year());
                meta.put("startYear", plan.startYear());
                meta.put("endYear", plan.endYear());
                meta.put("roleKeyword", plan.roleKeyword());
                meta.put("city", plan.city());
                meta.put("rowCount", rows.size());
                meta.put("readOnly", true);
                evidence.add(meta);

                for (Map<String, Object> row : rows) {
                    Map<String, Object> item = new LinkedHashMap<>(row);
                    item.put("evidenceType", plan.type().name().toLowerCase(Locale.ROOT));
                    item.put("source", sourceFor(plan.type()));
                    evidence.add(item);
                    if (evidence.size() >= MAX_TOTAL_EVIDENCE) break;
                }
            } catch (Exception ex) {
                String message = safeMessage(ex);
                warnings.add(plan.type().name() + " 查询失败：" + message);
                log.warn("动态数据库分析 {} 失败：{}", plan.type(), message);
            }
            if (evidence.size() >= MAX_TOTAL_EVIDENCE) break;
        }
        return new AnalysisResult(evidence, warnings, plannedByModel);
    }

    private List<QueryPlan> deterministicPlans(String question) {
        String value = question == null ? "" : question.toLowerCase(Locale.ROOT);
        Integer year = extractYear(value);
        String role = extractRoleKeyword(value);
        int limit = value.matches("(?s).*top\\s*([1-9]|10).*" ) ? extractTopLimit(value) : 10;

        if (value.matches("(?s).*(如何|怎么|怎样).*(学习路径|学习计划).*")) {
            return List.of(plan(QueryType.LEARNING_CONTEXT, year, null, null, role, "", 6));
        }
        if (value.matches("(?s).*(热门|最多|最高|排行|排名|需求量).*(岗位|职位).*" )
                || value.matches("(?s).*(岗位|职位).*(热门|最多|排行|排名|需求量).*")) {
            return List.of(plan(QueryType.TOP_ROLES, year, null, null, role, "", limit));
        }
        if (value.matches("(?s).*(新增.{0,8}技能|技能.{0,8}(新增|变化|弱化|增强)|能力演化).*")) {
            return List.of(plan(QueryType.EVOLUTION_EVENTS, year, null, null, role, "", 8));
        }
        if (value.matches("(?s).*(公司|企业).*(分布|排行|最多|招聘量).*")) {
            return List.of(plan(QueryType.TOP_COMPANIES, year, null, null, role, "", limit));
        }
        if (value.matches("(?s).*(行业|领域).*(分布|排行|最多|招聘量).*")) {
            return List.of(plan(QueryType.TOP_INDUSTRIES, year, null, null, role, "", limit));
        }
        if (value.matches("(?s).*(学历|教育背景).*(分布|要求|最多|占比).*")) {
            return List.of(plan(QueryType.EDUCATION_DISTRIBUTION, year, null, null, role, "", limit));
        }
        if (value.matches("(?s).*(技术栈).*(分布|排行|最多|热门).*")) {
            return List.of(plan(QueryType.TECH_STACK_DISTRIBUTION, year, null, null, role, "", limit));
        }
        if (value.matches("(?s).*(技能|技术栈).*(最多|热门|排行|需要|要求).*" )
                || value.matches("(?s).*(需要|要求).*(技能|技术栈).*")) {
            return List.of(plan(QueryType.TOP_SKILLS, year, null, null, role, "", limit));
        }
        if (value.matches("(?s).*(趋势|历年|逐年|增长率|变化趋势).*")) {
            return List.of(plan(QueryType.ROLE_TREND, year, year == null ? null : year - 4, year, role, "", 10));
        }
        if (value.matches("(?s).*(城市|地区|地域).*(分布|排行|最多).*")) {
            return List.of(plan(QueryType.TOP_CITIES, year, null, null, role, "", limit));
        }
        if (value.matches("(?s).*(薪资|薪酬|工资|月薪).*")) {
            return List.of(plan(QueryType.SALARY_SUMMARY, year, null, null, role, "", 10));
        }
        if (value.matches("(?s).*(一共|总共|总量|数量|多少).*(岗位|职位|jd).*")) {
            return List.of(plan(QueryType.MARKET_SUMMARY, year, null, null, role, "", 1));
        }
        if (value.matches("(?s).*(新岗位|新职位|萌芽|新兴岗位|候选岗位).*")) {
            return List.of(plan(QueryType.EMERGING_CANDIDATES, year, null, null, role, "", 8));
        }
        return List.of();
    }

    private List<QueryPlan> parsePlans(String response, List<String> warnings) {
        try {
            String json = stripCodeFence(response);
            JsonNode root = mapper.readTree(json);
            JsonNode queries = root.path("queries");
            if (!queries.isArray()) return List.of();

            List<QueryPlan> plans = new ArrayList<>();
            Set<String> dedupe = new LinkedHashSet<>();
            for (JsonNode node : queries) {
                QueryType type;
                try {
                    type = QueryType.valueOf(node.path("queryType").asText("").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                QueryPlan plan = plan(
                        type,
                        nullableInt(node.get("year")),
                        nullableInt(node.get("startYear")),
                        nullableInt(node.get("endYear")),
                        safeFilter(node.path("roleKeyword").asText(""), 100),
                        safeFilter(node.path("city").asText(""), 60),
                        node.path("limit").asInt(8)
                );
                String key = plan.toString();
                if (dedupe.add(key)) plans.add(plan);
                if (plans.size() >= MAX_PLANS) break;
            }
            return plans;
        } catch (Exception ex) {
            warnings.add("数据库分析计划 JSON 无法解析，已回退到常规证据检索");
            log.warn("解析数据库分析计划失败：{}", safeMessage(ex));
            return List.of();
        }
    }

    private List<Map<String, Object>> execute(QueryPlan plan) {
        return switch (plan.type()) {
            case TOP_ROLES -> topRoles(plan);
            case ROLE_TREND -> roleTrend(plan);
            case TOP_SKILLS -> topSkills(plan);
            case TOP_CITIES -> topCities(plan);
            case TOP_COMPANIES -> dimensionRanking(plan, "company", "company_name");
            case TOP_INDUSTRIES -> dimensionRanking(plan, "industry", "industry_name");
            case EDUCATION_DISTRIBUTION -> dimensionRanking(plan, "education", "education_requirement");
            case TECH_STACK_DISTRIBUTION -> dimensionRanking(plan, "tech_stack", "tech_stack");
            case MARKET_SUMMARY -> marketSummary(plan);
            case SALARY_SUMMARY -> salarySummary(plan);
            case JOB_SAMPLES -> jobSamples(plan);
            case EMERGING_CANDIDATES -> emergingCandidates(plan);
            case EVOLUTION_EVENTS -> evolutionEvents(plan);
            case MATCH_REPORTS -> matchReports(plan);
            case LEARNING_CONTEXT -> learningContext(plan);
            case SYSTEM_OVERVIEW -> List.of(new LinkedHashMap<>(governance.analysisSnapshot()));
        };
    }

    private List<Map<String, Object>> topRoles(QueryPlan plan) {
        Sql sql = jobFilter(plan, true);
        String evidenceKey = "COALESCE(duplicate_group,CONCAT('U-',raw_job_id))";
        String query = "SELECT /*+ MAX_EXECUTION_TIME(15000) */ title_standard AS role_name," +
                "COUNT(DISTINCT " + evidenceKey + ") AS job_count," +
                "COUNT(DISTINCT NULLIF(TRIM(company),'')) AS company_count," +
                "COUNT(DISTINCT CASE WHEN published_at IS NOT NULL AND MONTH(published_at)<=6 THEN " + evidenceKey + " END) AS first_half_count," +
                "COUNT(DISTINCT CASE WHEN published_at IS NOT NULL AND MONTH(published_at)>=7 THEN " + evidenceKey + " END) AS second_half_count," +
                "ROUND(AVG(quality_score),4) AS average_quality," +
                "ROUND(AVG(NULLIF((salary_min+salary_max)/2,0)),2) AS average_salary " +
                "FROM `" + RawJobGovernanceService.GOVERNED_TABLE + "` " + sql.where() +
                " GROUP BY title_standard ORDER BY job_count DESC LIMIT ?";
        sql.args().add(plan.limit());
        return raw.list(query, sql.args().toArray());
    }

    private List<Map<String, Object>> roleTrend(QueryPlan plan) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = baseWhere();
        int endYear = validYear(plan.endYear()) == null ? Year.now().getValue() : validYear(plan.endYear());
        int startYear = validYear(plan.startYear()) == null ? Math.max(2000, endYear - 5) : validYear(plan.startYear());
        where.append(" AND published_year BETWEEN ? AND ?");
        args.add(startYear);
        args.add(endYear);
        addLike(where, args, "title_standard", plan.roleKeyword());
        addLike(where, args, "city", plan.city());
        String query = "SELECT /*+ MAX_EXECUTION_TIME(15000) */ published_year," +
                "COUNT(DISTINCT COALESCE(duplicate_group,CONCAT('U-',raw_job_id))) AS job_count," +
                "COUNT(DISTINCT NULLIF(TRIM(company),'')) AS company_count," +
                "ROUND(AVG(NULLIF((salary_min+salary_max)/2,0)),2) AS average_salary " +
                "FROM `" + RawJobGovernanceService.GOVERNED_TABLE + "` " + where +
                " GROUP BY published_year ORDER BY published_year";
        return raw.list(query, args.toArray());
    }

    private List<Map<String, Object>> topSkills(QueryPlan plan) {
        Sql sql = jobFilter(plan, false);
        String query = "SELECT /*+ MAX_EXECUTION_TIME(15000) */ s.skill_name," +
                "COUNT(DISTINCT COALESCE(g.duplicate_group,CONCAT('U-',g.raw_job_id))) AS job_count," +
                "ROUND(SUM(g.duplicate_weight*s.confidence),2) AS weighted_support," +
                "SUM(CASE WHEN s.requirement_type='REQUIRED' THEN 1 ELSE 0 END) AS required_mentions," +
                "SUM(CASE WHEN s.requirement_type IN ('BONUS','PREFERRED') THEN 1 ELSE 0 END) AS bonus_mentions " +
                "FROM `" + RawJobGovernanceService.SKILL_TABLE + "` s " +
                "JOIN `" + RawJobGovernanceService.GOVERNED_TABLE + "` g ON g.raw_job_id=s.raw_job_id " +
                sql.where().replace("title_standard", "g.title_standard")
                        .replace("published_year", "g.published_year")
                        .replace("valid_for_analysis", "g.valid_for_analysis")
                        .replace("is_deleted", "g.is_deleted")
                        .replace("city", "g.city") +
                " GROUP BY s.skill_name ORDER BY job_count DESC,weighted_support DESC LIMIT ?";
        sql.args().add(plan.limit());
        return raw.list(query, sql.args().toArray());
    }

    private List<Map<String, Object>> topCities(QueryPlan plan) {
        Sql sql = jobFilter(plan, false);
        String query = "SELECT /*+ MAX_EXECUTION_TIME(15000) */ city," +
                "COUNT(DISTINCT COALESCE(duplicate_group,CONCAT('U-',raw_job_id))) AS job_count," +
                "COUNT(DISTINCT title_standard) AS role_count," +
                "ROUND(AVG(NULLIF((salary_min+salary_max)/2,0)),2) AS average_salary " +
                "FROM `" + RawJobGovernanceService.GOVERNED_TABLE + "` " + sql.where() +
                " AND city IS NOT NULL AND TRIM(city)<>'' GROUP BY city ORDER BY job_count DESC LIMIT ?";
        sql.args().add(plan.limit());
        return raw.list(query, sql.args().toArray());
    }

    private List<Map<String, Object>> salarySummary(QueryPlan plan) {
        Sql sql = jobFilter(plan, false);
        String query = "SELECT /*+ MAX_EXECUTION_TIME(15000) */ COUNT(*) AS salary_sample_count," +
                "ROUND(AVG(salary_min),2) AS average_salary_min," +
                "ROUND(AVG(salary_max),2) AS average_salary_max," +
                "ROUND(MIN(salary_min),2) AS minimum_salary," +
                "ROUND(MAX(salary_max),2) AS maximum_salary " +
                "FROM `" + RawJobGovernanceService.GOVERNED_TABLE + "` " + sql.where() +
                " AND salary_min IS NOT NULL AND salary_max IS NOT NULL";
        return raw.list(query, sql.args().toArray());
    }

    private List<Map<String, Object>> dimensionRanking(QueryPlan plan, String column, String alias) {
        Sql sql = jobFilter(plan, false);
        String query = "SELECT /*+ MAX_EXECUTION_TIME(15000) */ " + column + " AS " + alias + "," +
                "COUNT(DISTINCT COALESCE(duplicate_group,CONCAT('U-',raw_job_id))) AS job_count," +
                "COUNT(DISTINCT title_standard) AS role_count," +
                "COUNT(DISTINCT NULLIF(TRIM(company),'')) AS company_count " +
                "FROM `" + RawJobGovernanceService.GOVERNED_TABLE + "` " + sql.where() +
                " AND " + column + " IS NOT NULL AND TRIM(" + column + ")<>'' " +
                "GROUP BY " + column + " ORDER BY job_count DESC LIMIT ?";
        sql.args().add(plan.limit());
        return raw.list(query, sql.args().toArray());
    }

    private List<Map<String, Object>> marketSummary(QueryPlan plan) {
        Sql sql = jobFilter(plan, false);
        String query = "SELECT /*+ MAX_EXECUTION_TIME(15000) */ " +
                "COUNT(DISTINCT COALESCE(duplicate_group,CONCAT('U-',raw_job_id))) AS job_count," +
                "COUNT(DISTINCT NULLIF(TRIM(title_standard),'')) AS role_count," +
                "COUNT(DISTINCT NULLIF(TRIM(company),'')) AS company_count," +
                "COUNT(DISTINCT NULLIF(TRIM(city),'')) AS city_count," +
                "ROUND(AVG(NULLIF((salary_min+salary_max)/2,0)),2) AS average_salary " +
                "FROM `" + RawJobGovernanceService.GOVERNED_TABLE + "` " + sql.where();
        return raw.list(query, sql.args().toArray());
    }

    private List<Map<String, Object>> jobSamples(QueryPlan plan) {
        Sql sql = jobFilter(plan, false);
        String query = "SELECT /*+ MAX_EXECUTION_TIME(10000) */ raw_job_id,title_standard,company,city," +
                "published_year,tech_stack,level_name,salary_min,salary_max,quality_score," +
                "LEFT(description_clean,500) AS description_excerpt " +
                "FROM `" + RawJobGovernanceService.GOVERNED_TABLE + "` " + sql.where() +
                " ORDER BY quality_score DESC,raw_job_id DESC LIMIT ?";
        sql.args().add(plan.limit());
        return raw.list(query, sql.args().toArray());
    }

    private List<Map<String, Object>> emergingCandidates(QueryPlan plan) {
        String keyword = plan.roleKeyword();
        return store.list(
                "SELECT candidate_name,definition,required_skills,bonus_skills,scenarios,sample_size," +
                        "growth_rate,novelty_score,confidence,training_year,target_year " +
                        "FROM emerging_candidate WHERE (:keyword='' OR LOWER(candidate_name) LIKE LOWER(:pattern)) " +
                        "ORDER BY novelty_score DESC,confidence DESC LIMIT :limit",
                Map.of("keyword", keyword, "pattern", "%" + keyword + "%", "limit", plan.limit())
        );
    }

    private List<Map<String, Object>> evolutionEvents(QueryPlan plan) {
        String keyword = plan.roleKeyword();
        return store.list(
                "SELECT role_name,skill_name,change_type,old_value,new_value,explanation,evidence_count," +
                        "confidence,period_from,period_to FROM evolution_event " +
                        "WHERE (:keyword='' OR LOWER(role_name) LIKE LOWER(:pattern) " +
                        "OR LOWER(skill_name) LIKE LOWER(:pattern)) " +
                        "ORDER BY confidence DESC,evidence_count DESC LIMIT :limit",
                Map.of("keyword", keyword, "pattern", "%" + keyword + "%", "limit", plan.limit())
        );
    }

    private List<Map<String, Object>> matchReports(QueryPlan plan) {
        return store.list(
                "SELECT m.id,r.role_name,p.person_name,m.overall_score,m.skill_score,m.project_score," +
                        "m.matched_skills,m.missing_skills,m.suggestions,m.created_at " +
                        "FROM match_report m JOIN job_role r ON r.id=m.role_id " +
                        "JOIN resume_profile p ON p.id=m.resume_id ORDER BY m.id DESC LIMIT :limit",
                Map.of("limit", plan.limit())
        );
    }

    private List<Map<String, Object>> learningContext(QueryPlan plan) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("context_type", "learning_path_generation_policy");
        policy.put("input", "match_report.missing_skills + weeks + hours_per_week");
        policy.put("algorithm", "LearningPathPlanner 按技能缺口、先修关系和时间预算生成阶段步骤");
        policy.put("output", "阶段目标、学习内容、实践交付物、周数分配；最终形成岗位可验证作品集");
        policy.put("service", "LearningPlanningService.generate(matchId,weeks,hours)");
        result.add(policy);
        result.addAll(store.list(
                "SELECT l.id,l.title,l.weeks,l.objective,l.steps_json,r.role_name,p.person_name,l.created_at " +
                        "FROM learning_path l JOIN match_report m ON m.id=l.match_id " +
                        "JOIN job_role r ON r.id=m.role_id JOIN resume_profile p ON p.id=m.resume_id " +
                        "ORDER BY l.id DESC LIMIT 3",
                Map.of()
        ));
        result.addAll(store.list(
                "SELECT m.id AS match_id,r.role_name,p.person_name,m.overall_score,m.missing_skills,m.suggestions " +
                        "FROM match_report m JOIN job_role r ON r.id=m.role_id " +
                        "JOIN resume_profile p ON p.id=m.resume_id ORDER BY m.id DESC LIMIT 3",
                Map.of()
        ));
        return result;
    }

    private Sql jobFilter(QueryPlan plan, boolean requireRole) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = baseWhere();
        Integer year = validYear(plan.year());
        if (year != null) {
            where.append(" AND published_year=?");
            args.add(year);
        }
        addLike(where, args, "title_standard", plan.roleKeyword());
        addLike(where, args, "city", plan.city());
        if (requireRole) where.append(" AND title_standard IS NOT NULL AND TRIM(title_standard)<>''");
        return new Sql(where.toString(), args);
    }

    private static StringBuilder baseWhere() {
        return new StringBuilder("WHERE valid_for_analysis=1 AND is_deleted=0");
    }

    private static void addLike(StringBuilder where, List<Object> args, String column, String value) {
        if (value == null || value.isBlank()) return;
        where.append(" AND LOWER(").append(column).append(") LIKE ?");
        args.add("%" + value.toLowerCase(Locale.ROOT) + "%");
    }

    private static QueryPlan plan(
            QueryType type,
            Integer year,
            Integer startYear,
            Integer endYear,
            String roleKeyword,
            String city,
            int limit
    ) {
        return new QueryPlan(
                type,
                validYear(year),
                validYear(startYear),
                validYear(endYear),
                safeFilter(roleKeyword, 100),
                safeFilter(city, 60),
                Math.max(1, Math.min(limit, 10))
        );
    }

    private static Integer extractYear(String value) {
        Matcher matcher = YEAR_PATTERN.matcher(value == null ? "" : value);
        if (!matcher.find()) return null;
        int year = Integer.parseInt(matcher.group(1));
        return validYear(year < 100 ? 2000 + year : year);
    }

    private static int extractTopLimit(String value) {
        Matcher matcher = Pattern.compile("top\\s*([1-9]|10)", Pattern.CASE_INSENSITIVE).matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 10;
    }

    private static String extractRoleKeyword(String value) {
        if (value == null) return "";
        Matcher latin = Pattern.compile("[a-z][a-z0-9+#.\\-]{1,30}", Pattern.CASE_INSENSITIVE).matcher(value);
        if (latin.find() && !Set.of("top", "sql", "api").contains(latin.group().toLowerCase(Locale.ROOT))) {
            return latin.group();
        }
        Matcher role = Pattern.compile("([\\p{IsHan}]{2,12}(?:工程师|开发|经理|专员|分析师|架构师|算法|运维|测试))").matcher(value);
        return role.find() ? role.group(1) : "";
    }

    private static Integer nullableInt(JsonNode node) {
        return node == null || node.isNull() || !node.canConvertToInt() ? null : node.asInt();
    }

    private static Integer validYear(Integer value) {
        return value != null && value >= 2000 && value <= 2100 ? value : null;
    }

    private static String safeFilter(String value, int max) {
        if (value == null) return "";
        String result = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return result.length() <= max ? result : result.substring(0, max);
    }

    private static String stripCodeFence(String value) {
        String result = value == null ? "" : value.trim();
        if (result.startsWith("```")) {
            result = result.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }
        int start = result.indexOf('{');
        int end = result.lastIndexOf('}');
        return start >= 0 && end > start ? result.substring(start, end + 1) : result;
    }

    private static String sourceFor(QueryType type) {
        return switch (type) {
            case TOP_ROLES, ROLE_TREND, TOP_SKILLS, TOP_CITIES, TOP_COMPANIES, TOP_INDUSTRIES,
                    EDUCATION_DISTRIBUTION, TECH_STACK_DISTRIBUTION, MARKET_SUMMARY,
                    SALARY_SUMMARY, JOB_SAMPLES, SYSTEM_OVERVIEW ->
                    "career_data_governance (read-only parameterized query)";
            case EMERGING_CANDIDATES -> "zhitu_business_store.emerging_candidate";
            case EVOLUTION_EVENTS -> "zhitu_business_store.evolution_event";
            case MATCH_REPORTS -> "zhitu_business_store.match_report";
            case LEARNING_CONTEXT -> "LearningPlanningService + learning_path + match_report";
        };
    }

    private static String safeMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) cursor = cursor.getCause();
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) message = cursor.getClass().getSimpleName();
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    public record AnalysisResult(
            List<Map<String, Object>> evidence,
            List<String> warnings,
            boolean plannedByModel
    ) {
    }

    private record QueryPlan(
            QueryType type,
            Integer year,
            Integer startYear,
            Integer endYear,
            String roleKeyword,
            String city,
            int limit
    ) {
    }

    private record Sql(String where, List<Object> args) {
    }

    private enum QueryType {
        TOP_ROLES,
        ROLE_TREND,
        TOP_SKILLS,
        TOP_CITIES,
        TOP_COMPANIES,
        TOP_INDUSTRIES,
        EDUCATION_DISTRIBUTION,
        TECH_STACK_DISTRIBUTION,
        MARKET_SUMMARY,
        SALARY_SUMMARY,
        JOB_SAMPLES,
        EMERGING_CANDIDATES,
        EVOLUTION_EVENTS,
        MATCH_REPORTS,
        LEARNING_CONTEXT,
        SYSTEM_OVERVIEW
    }
}
