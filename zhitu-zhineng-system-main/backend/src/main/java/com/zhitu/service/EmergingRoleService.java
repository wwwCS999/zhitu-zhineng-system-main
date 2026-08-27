package com.zhitu.service;

import com.zhitu.common.TextUtils;
import com.zhitu.engine.EmergingRoleScoringEngine;
import com.zhitu.engine.TrustScoreEngine;
import com.zhitu.repository.RawDatabaseClient;
import com.zhitu.repository.Store;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 年度动态新岗位发现。
 *
 * V6 关键约束：选择目标年份 Y 时，只使用 Y-1 年治理 JD 生成高潜候选，
 * 不读取 Y 年真实岗位参与候选排序，从而避免未来数据泄漏。
 * Y 年真实数据只允许在“年度验证”模块中用于回测。
 */
@Service
public class EmergingRoleService {

    private static final Pattern LEVEL = Pattern.compile(
            "(?i)(初级|中级|高级|资深|专家|首席|实习|校招|社招|junior|senior|lead|principal)"
    );
    private static final Pattern BRACKET = Pattern.compile("[（(【\\[].{0,30}?[）)】\\]]");
    private static final Pattern NOISE = Pattern.compile(
            "(?i)(急聘|诚聘|高薪|招聘|直招|五险一金|六险一金|七险一金|双休|大小周|" +
                    "包吃住|包食宿|入职|无实习|不限经验|可居家|居家办公|接受应届生|年终奖)"
    );
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern ROLE_WORD = Pattern.compile(
            "(工程师|架构师|经理|专家|顾问|设计师|分析师|科学家|开发|运维|运营|产品|客服|电销|审核|标注员|销售|文员|普工|操作工|业务员)"
    );
    private static final Pattern GENERIC_OR_LOW_VALUE_ROLE = Pattern.compile(
            "^(?:居家)?(?:客服|电销|审核|标注员|销售|文员|普工|操作工|业务员)(?:专员|人员|岗位)?$"
    );
    private static final Pattern TITLE_SPECIALIZATION = Pattern.compile(
            "^(.{2,24}?(?:工程师|架构师|经理|专家|顾问|设计师|分析师|科学家))\\s*[-—_]\\s*([\\p{IsHan}A-Za-z0-9]{2,12})$"
    );
    private static final Pattern LEADING_ENUMERATION = Pattern.compile(
            "^[\\s,，、;；]*(?:第?[0-9一二三四五六七八九十]+(?:[、.．)）:：-]|\\s+))+\\s*"
    );
    private static final Pattern RESPONSIBILITY_LABEL = Pattern.compile(
            "^(?:(?:岗位|工作|职位|核心|主要)?职责|职责描述|职位描述|工作内容|核心工作(?:包括)?)\\s*[:：]\\s*"
    );
    private static final Pattern REQUIREMENT_SENTENCE = Pattern.compile(
            "^(?:任职要求|岗位要求|任职资格|职位要求|要求|需|须|应聘|具备|持有|年龄|学历|本科|大专|硕士|经验|福利|薪资|待遇|身体|职业健康|无色盲|无色弱).*"
    );
    private static final Pattern RESPONSIBILITY_WORD = Pattern.compile(
            "(?i).*(负责|参与|设计|开发|研发|构建|搭建|维护|优化|部署|落地|实现|评测|治理|规划|分析|推进|支持).*"
    );

    private final Store store;
    private final RawDatabaseClient raw;
    private final RawJobGovernanceService governance;
    private final TrustScoreEngine trust;
    private final EmergingRoleScoringEngine scoring;

    public EmergingRoleService(
            Store store,
            RawDatabaseClient raw,
            RawJobGovernanceService governance,
            TrustScoreEngine trust,
            EmergingRoleScoringEngine scoring
    ) {
        this.store = store;
        this.raw = raw;
        this.governance = governance;
        this.trust = trust;
        this.scoring = scoring;
    }

    /** 兼容旧调用：默认预测治理数据中最新可验证年份。 */
    public Map<String, Object> discover() {
        return discover(latestTargetYear());
    }

    /**
     * 使用 targetYear-1 年 JD 预测 targetYear 高潜岗位。
     */
    public Map<String, Object> discover(int targetYear) {
        governance.assertReadyForAnalysis();
        governance.ensureSchema();
        ensureCandidateSchema();
        validateTargetYear(targetYear);

        int trainingYear = targetYear - 1;
        Map<String, RoleAggregate> training = aggregateTrainingYear(trainingYear, 1600);
        if (training.isEmpty()) {
            throw new IllegalStateException(trainingYear + " 年没有可用于探新的治理 JD");
        }

        List<RoleCandidate> ranked = new ArrayList<>();
        for (RoleAggregate role : training.values()) {
            if (role.sampleCount < 6 || role.normalized.length() < 2) continue;
            // 多源可信门槛：新兴岗位必须得到至少 2 家独立企业 + 2 个独立来源的交叉印证，过滤单源噪声。
            if (role.companyCount < 2 || role.sourceCount < 2) continue;

            long dated = role.firstHalfCount + role.secondHalfCount;
            double lateShare = dated == 0
                    ? 0.5D
                    : role.secondHalfCount / (double) Math.max(1L, dated);
            double intraYearGrowth = role.firstHalfCount == 0
                    ? (role.secondHalfCount > 0 ? 1D : 0.5D)
                    : clamp01(0.5D + 0.5D * (
                    (role.secondHalfCount - role.firstHalfCount) /
                            (double) Math.max(1L, role.secondHalfCount + role.firstHalfCount)
            ));

            // “名称/组合新颖度”不借用目标年的真实数据，只看训练年内部后半年是否出现/加速。
            double emergence = role.firstHalfCount == 0 && role.secondHalfCount > 0
                    ? 1D
                    : clamp01(0.30D + 0.70D * lateShare);
            double supportProxy = clamp01(Math.log1p(role.sampleCount) / Math.log(201D));
            double diversity = clamp01(0.45D
                    + Math.min(0.30D, role.companyCount / 80D)
                    + Math.min(0.25D, role.sourceCount / 12D));

            double noveltyScore = scoring.novelty(
                    emergence,
                    supportProxy,
                    intraYearGrowth,
                    (int) Math.min(Integer.MAX_VALUE, role.sourceCount),
                    (int) Math.min(Integer.MAX_VALUE, role.sampleCount)
            );
            double confidence = trust.confidence(
                    (int) Math.min(Integer.MAX_VALUE, role.sampleCount),
                    (int) Math.min(Integer.MAX_VALUE, role.sourceCount),
                    clamp01(role.avgQuality),
                    0.08,
                    diversity
            );

            // 高潜候选强调“后半年增长 + 多企业/多来源支持”，不是稳定岗位排行榜。
            // 收紧判定阈值：提升新颖度与置信度门槛，显著降低稳定岗位被误判为新兴岗位的假阳性率。
            if (noveltyScore < 0.52 || confidence < 0.55) continue;
            // 强涌现证据二选一：要么“后半年首次出现”，要么“后半年占比高且年内明显加速”。
            boolean brandNewInLateYear = role.firstHalfCount == 0 && role.secondHalfCount > 0;
            boolean accelerating = intraYearGrowth >= 0.54 && lateShare >= 0.58;
            if (!brandNewInLateYear && !accelerating) continue;

            ranked.add(new RoleCandidate(role, noveltyScore, intraYearGrowth, lateShare, confidence));
        }

        ranked.sort(
                Comparator.comparingDouble(RoleCandidate::noveltyScore).reversed()
                        .thenComparing(Comparator.comparingDouble(RoleCandidate::growthScore).reversed())
                        .thenComparing(Comparator.comparingLong((RoleCandidate c) -> c.aggregate.sampleCount).reversed())
        );
        if (ranked.size() > 30) ranked = new ArrayList<>(ranked.subList(0, 30));

        store.update(
                "DELETE FROM emerging_candidate WHERE status='AUTO_CANDIDATE' AND target_year=:targetYear",
                Map.of("targetYear", targetYear)
        );

        int created = 0;
        for (RoleCandidate candidate : ranked) {
            RoleAggregate role = candidate.aggregate;
            List<SkillRow> skillRows = loadSkills(trainingYear, role.variants);
            List<String> required = requiredSkills(skillRows);
            List<String> bonus = bonusSkills(skillRows, required);
            List<String> responsibilities = extractResponsibilities(trainingYear, role.variants, role.stack);
            List<String> scenarios = deriveScenarios(trainingYear, role.variants, role.stack);

            String roleName = cleanRoleTitle(role.displayTitle);
            if (!isPlausibleRoleTitle(roleName)) continue;
            String definition = buildDefinition(
                    roleName,
                    role.stack,
                    responsibilities,
                    scenarios,
                    trainingYear,
                    targetYear
            );
            double risk = trust.hallucinationRisk(
                    (int) Math.min(Integer.MAX_VALUE, role.sampleCount),
                    (int) Math.min(Integer.MAX_VALUE, role.sourceCount),
                    candidate.confidence,
                    false
            );

            store.insert(
                    "INSERT INTO emerging_candidate(" +
                            "candidate_name,cluster_key,definition,responsibilities,required_skills,bonus_skills,scenarios," +
                            "sample_size,source_count,growth_rate,novelty_score,confidence,hallucination_risk,status," +
                            "training_year,target_year,forecast_method" +
                            ") VALUES(:name,:cluster,:definition,:responsibilities,:required,:bonus,:scenarios," +
                            ":sampleSize,:sourceCount,:growth,:novelty,:confidence,:risk,'AUTO_CANDIDATE'," +
                            ":trainingYear,:targetYear,'PREVIOUS_YEAR_ONLY')",
                    params(
                            "name", roleName,
                            "cluster", role.normalized,
                            "definition", definition,
                            "responsibilities", TextUtils.jsonArray(responsibilities),
                            "required", TextUtils.jsonArray(required),
                            "bonus", TextUtils.jsonArray(bonus),
                            "scenarios", TextUtils.jsonArray(scenarios),
                            "sampleSize", (int) Math.min(Integer.MAX_VALUE, role.sampleCount),
                            "sourceCount", (int) Math.min(Integer.MAX_VALUE, role.sourceCount),
                            "growth", round6(candidate.growthScore),
                            "novelty", round6(candidate.noveltyScore),
                            "confidence", round6(candidate.confidence),
                            "risk", round6(risk),
                            "trainingYear", trainingYear,
                            "targetYear", targetYear
                    )
            );
            created++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", RawJobGovernanceService.GOVERNED_TABLE);
        result.put("trainingYear", trainingYear);
        result.put("targetYear", targetYear);
        result.put("trainingRoles", training.size());
        result.put("candidates", created);
        result.put("method", "PREVIOUS_YEAR_ONLY_INTRA_YEAR_EMERGENCE");
        result.put("leakageGuard", true);
        result.put("snapshot", governance.analysisSnapshot());
        result.put("note", "当前结果基于本次点击更新时已经治理完成的 JD 快照；每新增约 100 条治理数据后可重新运行更新。最终比赛结果建议在全量治理完成后再更新一次。");
        return result;
    }

    /** 目标年份下拉列表：只有存在前一年治理数据的年份才出现。 */
    public List<Map<String, Object>> availableTargetYears() {
        governance.ensureSchema();
        String jobs = "`" + RawJobGovernanceService.GOVERNED_TABLE + "`";
        List<Map<String, Object>> rows = raw.list(
                "SELECT published_year AS training_year,COUNT(*) AS training_rows " +
                        "FROM " + jobs + " WHERE valid_for_analysis=1 AND published_year BETWEEN 2000 AND 2099 " +
                        "GROUP BY published_year ORDER BY published_year"
        );
        if (rows.isEmpty()) return List.of();

        Set<Integer> observedYears = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            observedYears.add(number(row.get("training_year")).intValue());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int trainingYear = number(row.get("training_year")).intValue();
            int targetYear = trainingYear + 1;
            // 必须真实存在目标年份数据，才能在后续年度验证中形成闭环。
            if (!observedYears.contains(targetYear)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("trainingYear", trainingYear);
            item.put("targetYear", targetYear);
            item.put("trainingRows", number(row.get("training_rows")).longValue());
            item.put("label", trainingYear + " JD → " + targetYear + " 高潜候选");
            result.add(item);
        }
        result.sort(Comparator.comparingInt(item -> -number(item.get("targetYear")).intValue()));
        return result;
    }

    public List<Map<String, Object>> candidates(int targetYear) {
        ensureCandidateSchema();
        List<Map<String, Object>> rows = store.list(
                "SELECT * FROM emerging_candidate WHERE target_year=:targetYear " +
                        "ORDER BY novelty_score DESC,confidence DESC,sample_size DESC",
                Map.of("targetYear", targetYear)
        );
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> source : rows) {
            String roleName = cleanRoleTitle(text(source.get("candidate_name")));
            if (!isPlausibleRoleTitle(roleName)) continue;

            LinkedHashSet<String> responsibilitySet = new LinkedHashSet<>();
            for (String item : TextUtils.jsonList(text(source.get("responsibilities")))) {
                String cleaned = cleanupSentence(item);
                if (isResponsibility(cleaned)) responsibilitySet.add(cleaned);
            }
            List<String> responsibilities = new ArrayList<>(responsibilitySet).stream().limit(4).toList();
            if (responsibilities.size() < 2) {
                responsibilities = fallbackResponsibilities(inferStack(source));
            }
            List<String> scenarios = TextUtils.jsonList(text(source.get("scenarios")));
            int trainingYear = number(source.get("training_year")).intValue();
            int rowTargetYear = number(source.get("target_year")).intValue();

            Map<String, Object> row = new LinkedHashMap<>(source);
            row.put("candidate_name", roleName);
            row.put("cluster_key", normalizeTitle(roleName));
            row.put("responsibilities", responsibilities);
            row.put("definition", buildDefinition(
                    roleName,
                    inferStack(source),
                    responsibilities,
                    scenarios,
                    trainingYear,
                    rowTargetYear
            ));
            result.add(row);
        }
        return result;
    }

    public List<Map<String, Object>> candidates() {
        int targetYear = latestTargetYear();
        return candidates(targetYear);
    }

    private int latestTargetYear() {
        List<Map<String, Object>> years = availableTargetYears();
        if (years.isEmpty()) {
            throw new IllegalStateException("治理数据中暂时没有可用于年度探新的年份");
        }
        return number(years.get(0).get("targetYear")).intValue();
    }

    private void validateTargetYear(int targetYear) {
        boolean exists = availableTargetYears().stream()
                .anyMatch(item -> number(item.get("targetYear")).intValue() == targetYear);
        if (!exists) {
            throw new IllegalArgumentException(
                    "目标年份 " + targetYear + " 不可用：必须存在 " + (targetYear - 1) + " 年治理 JD，且目标年需存在真实数据用于后续验证。"
            );
        }
    }

    private void ensureCandidateSchema() {
        try {
            store.update("ALTER TABLE emerging_candidate ADD COLUMN IF NOT EXISTS training_year INT", Map.of());
            store.update("ALTER TABLE emerging_candidate ADD COLUMN IF NOT EXISTS target_year INT", Map.of());
            store.update("ALTER TABLE emerging_candidate ADD COLUMN IF NOT EXISTS forecast_method VARCHAR(80)", Map.of());
        } catch (Exception ignored) {
            // schema.sql 已包含这些字段；这里用于兼容热更新后的旧 H2 会话。
        }
    }

    private Map<String, RoleAggregate> aggregateTrainingYear(int year, int limit) {
        String jobs = "`" + RawJobGovernanceService.GOVERNED_TABLE + "`";
        String evidenceKey = "COALESCE(duplicate_group,CONCAT('U-',raw_job_id))";
        String sql = "SELECT title_standard,MAX(tech_stack) AS tech_stack," +
                "COUNT(DISTINCT " + evidenceKey + ") AS sample_count," +
                "COUNT(DISTINCT CASE WHEN published_at IS NOT NULL AND MONTH(published_at)<=6 THEN " + evidenceKey + " END) AS h1_count," +
                "COUNT(DISTINCT CASE WHEN published_at IS NOT NULL AND MONTH(published_at)>=7 THEN " + evidenceKey + " END) AS h2_count," +
                "COUNT(DISTINCT NULLIF(TRIM(company),'')) AS company_count," +
                "COUNT(DISTINCT NULLIF(TRIM(source_name),'')) AS source_count," +
                "AVG(quality_score) AS avg_quality " +
                "FROM " + jobs + " WHERE valid_for_analysis=1 AND published_year=? " +
                "AND title_standard IS NOT NULL AND TRIM(title_standard)<>'' " +
                "GROUP BY title_standard ORDER BY sample_count DESC" + (limit > 0 ? " LIMIT " + limit : "");

        Map<String, RoleAggregate> result = new LinkedHashMap<>();
        for (Map<String, Object> row : raw.list(sql, year)) {
            String title = text(row.get("title_standard"));
            String displayTitle = cleanRoleTitle(title);
            if (!isPlausibleRoleTitle(displayTitle)) continue;
            String normalized = normalizeTitle(displayTitle);
            if (normalized.length() < 2) continue;

            long samples = number(row.get("sample_count")).longValue();
            long h1 = number(row.get("h1_count")).longValue();
            long h2 = number(row.get("h2_count")).longValue();
            long companies = number(row.get("company_count")).longValue();
            long sources = number(row.get("source_count")).longValue();
            double quality = number(row.get("avg_quality")).doubleValue();
            String stack = text(row.get("tech_stack"));

            RoleAggregate next = new RoleAggregate(
                    normalized,
                    displayTitle,
                    stack.isBlank() ? "新一代信息技术" : stack,
                    samples,
                    h1,
                    h2,
                    companies,
                    sources,
                    quality,
                    new LinkedHashSet<>(List.of(title))
            );
            result.merge(normalized, next, RoleAggregate::merge);
        }
        return result;
    }

    private List<SkillRow> loadSkills(int year, Set<String> variants) {
        if (variants.isEmpty()) return List.of();
        String jobs = "`" + RawJobGovernanceService.GOVERNED_TABLE + "`";
        String skills = "`" + RawJobGovernanceService.SKILL_TABLE + "`";
        List<String> titles = variants.stream().limit(8).toList();
        String placeholders = String.join(",", titles.stream().map(v -> "?").toList());
        String sql = "SELECT s.skill_name,s.requirement_type," +
                "SUM(g.duplicate_weight*s.confidence) AS weighted_support," +
                "COUNT(DISTINCT COALESCE(g.duplicate_group,CONCAT('U-',g.raw_job_id))) AS mention_count " +
                "FROM " + skills + " s JOIN " + jobs + " g ON g.raw_job_id=s.raw_job_id " +
                "WHERE g.valid_for_analysis=1 AND g.published_year=? AND g.title_standard IN (" + placeholders + ") " +
                "GROUP BY s.skill_name,s.requirement_type ORDER BY weighted_support DESC LIMIT 60";
        List<Object> args = new ArrayList<>();
        args.add(year);
        args.addAll(titles);

        List<SkillRow> result = new ArrayList<>();
        for (Map<String, Object> row : raw.list(sql, args.toArray())) {
            result.add(new SkillRow(
                    text(row.get("skill_name")),
                    text(row.get("requirement_type")),
                    number(row.get("weighted_support")).doubleValue(),
                    number(row.get("mention_count")).longValue()
            ));
        }
        return result;
    }

    private List<String> requiredSkills(List<SkillRow> rows) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        rows.stream()
                .filter(row -> "REQUIRED".equalsIgnoreCase(row.requirementType))
                .sorted(Comparator.comparingDouble(SkillRow::weightedSupport).reversed())
                .forEach(row -> { if (result.size() < 8) result.add(row.name); });
        if (result.size() < 5) {
            rows.stream()
                    .filter(row -> !"PREFERRED".equalsIgnoreCase(row.requirementType)
                            && !"BONUS".equalsIgnoreCase(row.requirementType))
                    .sorted(Comparator.comparingDouble(SkillRow::weightedSupport).reversed())
                    .forEach(row -> { if (result.size() < 8) result.add(row.name); });
        }
        return new ArrayList<>(result);
    }

    private List<String> bonusSkills(List<SkillRow> rows, List<String> required) {
        Set<String> requiredSet = new LinkedHashSet<>(required);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        rows.stream()
                .filter(row -> "PREFERRED".equalsIgnoreCase(row.requirementType)
                        || "BONUS".equalsIgnoreCase(row.requirementType))
                .filter(row -> !requiredSet.contains(row.name))
                .sorted(Comparator.comparingDouble(SkillRow::weightedSupport).reversed())
                .forEach(row -> { if (result.size() < 6) result.add(row.name); });
        if (result.size() < 3) {
            rows.stream()
                    .filter(row -> !requiredSet.contains(row.name))
                    .sorted(Comparator.comparingDouble(SkillRow::weightedSupport).reversed())
                    .forEach(row -> { if (result.size() < 6) result.add(row.name); });
        }
        return new ArrayList<>(result);
    }

    private List<String> extractResponsibilities(int year, Set<String> variants, String stack) {
        if (variants.isEmpty()) return fallbackResponsibilities(stack);
        List<String> titles = variants.stream().limit(8).toList();
        String placeholders = String.join(",", titles.stream().map(v -> "?").toList());
        String jobs = "`" + RawJobGovernanceService.GOVERNED_TABLE + "`";
        String sql = "SELECT description_clean FROM " + jobs +
                " WHERE valid_for_analysis=1 AND published_year=? AND title_standard IN (" + placeholders + ") " +
                "AND description_clean IS NOT NULL ORDER BY quality_score DESC LIMIT 120";
        List<Object> args = new ArrayList<>();
        args.add(year);
        args.addAll(titles);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : raw.list(sql, args.toArray())) {
            String description = text(row.get("description_clean"));
            for (String piece : description.split(
                    "[。；;\\n]|(?=\\s*(?:[0-9]{1,2}|[一二三四五六七八九十]+)[、.．)）])"
            )) {
                String sentence = cleanupSentence(piece);
                if (sentence.length() < 8 || sentence.length() > 90) continue;
                if (!isResponsibility(sentence)) continue;
                counts.merge(sentence, 1, Integer::sum);
            }
        }

        List<String> result = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().length()))
                .map(Map.Entry::getKey)
                .limit(4)
                .toList();
        return result.size() >= 2 ? result : fallbackResponsibilities(stack);
    }

    private List<String> deriveScenarios(int year, Set<String> variants, String stack) {
        List<String> titles = variants.stream().limit(8).toList();
        if (titles.isEmpty()) return List.of(defaultScenario(stack));
        String placeholders = String.join(",", titles.stream().map(v -> "?").toList());
        String jobs = "`" + RawJobGovernanceService.GOVERNED_TABLE + "`";
        String sql = "SELECT NULLIF(TRIM(industry),'') AS industry_name,COUNT(*) AS cnt FROM " + jobs +
                " WHERE valid_for_analysis=1 AND published_year=? AND title_standard IN (" + placeholders + ") " +
                "AND industry IS NOT NULL AND TRIM(industry)<>'' GROUP BY NULLIF(TRIM(industry),'') " +
                "ORDER BY cnt DESC LIMIT 3";
        List<Object> args = new ArrayList<>();
        args.add(year);
        args.addAll(titles);

        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Map<String, Object> row : raw.list(sql, args.toArray())) {
            String industry = text(row.get("industry_name"));
            if (!industry.isBlank()) result.add(industry + " · " + defaultScenario(stack));
        }
        if (result.isEmpty()) result.add(defaultScenario(stack));
        return new ArrayList<>(result);
    }

    private String buildDefinition(
            String roleName,
            String stack,
            List<String> responsibilities,
            List<String> scenarios,
            int trainingYear,
            int targetYear
    ) {
        String responsibility = responsibilities.isEmpty() ? "完成核心技术方案设计与工程落地" : responsibilities.get(0);
        String scenario = scenarios.isEmpty() ? defaultScenario(stack) : scenarios.get(0);
        return "基于 " + trainingYear + " 年治理 JD 的岗位增长、后半年动量和多源证据，预测 " +
                targetYear + " 年高潜岗位“" + roleName + "”。主要面向" + scenario +
                "，核心工作聚焦于" + responsibility + "。";
    }

    private List<String> fallbackResponsibilities(String stack) {
        String scenario = defaultScenario(stack);
        String value = stack == null ? "" : stack;
        if (value.contains("新能源") || value.contains("风电") || value.contains("电力")) {
            return List.of(
                    "执行风电机组及配套设备的日常巡检、维护与故障处理",
                    "分析设备运行数据并制定预防性维护方案",
                    "落实检修安全规范并记录设备健康状态",
                    "结合告警和工单持续优化运维效率"
            );
        }
        if (value.contains("数据库") || value.contains("基础设施")) {
            return List.of(
                    "设计数据库架构、部署拓扑与高可用方案",
                    "负责数据库性能分析、容量规划和故障处置",
                    "制定备份恢复、数据安全与变更管理规范",
                    "协同业务团队完成数据库方案落地和持续优化"
            );
        }
        return List.of(
                "分析业务需求并完成" + stack + "技术方案设计",
                "构建、测试并部署岗位对应的核心技术系统",
                "围绕" + scenario + "持续进行效果评估与工程优化",
                "跟踪相关技术趋势并沉淀可复用的工程能力"
        );
    }

    private String defaultScenario(String stack) {
        String value = stack == null ? "" : stack;
        if (value.contains("新能源") || value.contains("风电") || value.contains("电力")) return "新能源发电、设备巡检与预测性维护";
        if (value.contains("数据库") || value.contains("基础设施")) return "数据库基础设施、数据平台与高可用系统";
        if (value.contains("大模型")) return "企业知识库、智能客服与智能体业务自动化";
        if (value.contains("大数据")) return "实时数仓、数据治理与经营分析";
        if (value.contains("物联网")) return "工业物联网、边缘感知与智能设备协同";
        if (value.contains("智能系统")) return "工业智能、机器人与数字孪生系统";
        if (value.contains("后端")) return "企业级平台、微服务与高并发业务系统";
        return "人工智能应用、智能决策与数字化业务场景";
    }

    private String normalizeTitle(String title) {
        String value = Normalizer.normalize(cleanRoleTitle(title), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        value = BRACKET.matcher(value).replaceAll(" ");
        value = LEVEL.matcher(value).replaceAll(" ");
        value = NOISE.matcher(value).replaceAll(" ");
        value = value.replaceAll("[|丨/\\\\]+", " ");
        return SPACE.matcher(value).replaceAll(" ").trim();
    }

    String cleanRoleTitle(String title) {
        String value = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFKC)
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("(?i)(?:月薪|年薪|薪资)?\\s*\\d+(?:\\.\\d+)?\\s*[kKwW千万元]*\\s*[-—~至]\\s*\\d+(?:\\.\\d+)?\\s*[kKwW千万元]*", " ");
        value = BRACKET.matcher(value).replaceAll(" ");
        value = NOISE.matcher(value).replaceAll(" ");
        value = SPACE.matcher(value).replaceAll(" ").trim();

        var specialization = TITLE_SPECIALIZATION.matcher(value);
        if (specialization.matches()) {
            value = specialization.group(1).trim() + "（" + specialization.group(2).trim() + "方向）";
        }
        return value
                .replaceAll("^[,，、;；|丨/\\\\:：·•\\s-]+", "")
                .replaceAll("[,，、;；|丨/\\\\:：·•\\s-]+$", "")
                .trim();
    }

    boolean isPlausibleRoleTitle(String title) {
        String value = cleanRoleTitle(title);
        if (value.length() < 2 || value.length() > 36) return false;
        if (GENERIC_OR_LOW_VALUE_ROLE.matcher(value).matches()) return false;
        String[] parts = value.split("[,，、/|丨]+", -1);
        int roleParts = 0;
        for (String part : parts) {
            if (ROLE_WORD.matcher(part).find()) roleParts++;
        }
        if (parts.length > 1 && roleParts > 1) return false;
        return value.matches(".*[\\p{IsHan}A-Za-z].*") && ROLE_WORD.matcher(value).find();
    }

    String cleanupSentence(String value) {
        if (value == null) return "";
        String result = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("^[◆●•·\\-*]+", "")
                .replaceAll("\\s+", " ")
                .trim();
        for (int index = 0; index < 5; index++) {
            String previous = result;
            result = LEADING_ENUMERATION.matcher(result).replaceFirst("").trim();
            result = RESPONSIBILITY_LABEL.matcher(result).replaceFirst("").trim();
            if (result.equals(previous)) break;
        }
        result = result.replaceAll("^[,，、;；:：\\s]+", "").trim();
        return result.length() > 90 ? result.substring(0, 90) : result;
    }

    boolean isResponsibility(String sentence) {
        if (sentence == null || sentence.isBlank()) return false;
        String value = cleanupSentence(sentence);
        if (value.length() < 8 || REQUIREMENT_SENTENCE.matcher(value).matches()) return false;
        if (value.matches(".*(具备|证书|作业证|体检|色盲|色弱|薪资|福利|五险|六险|七险|学历|经验要求|任职资格).*")) {
            return false;
        }
        return RESPONSIBILITY_WORD.matcher(value).matches();
    }

    private String inferStack(Map<String, Object> candidate) {
        String value = text(candidate.get("candidate_name")) + " " + text(candidate.get("cluster_key"));
        if (value.matches(".*(风电|新能源|电力|光伏).*")) return "新能源与工业运维";
        if (value.matches(".*(数据库|DBA|基础设施|数据平台).*")) return "数据库与基础设施";
        if (value.matches(".*(大模型|智能体|RAG|算法|人工智能).*")) return "大模型应用";
        if (value.matches(".*(Java|后端|微服务).*")) return "后端开发";
        return "新一代信息技术";
    }

    private Map<String, Object> params(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private Number number(Object value) {
        return value instanceof Number n ? n : 0;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double clamp01(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000D) / 1_000_000D;
    }

    private record SkillRow(String name, String requirementType, double weightedSupport, long mentions) {
    }

    private record RoleCandidate(
            RoleAggregate aggregate,
            double noveltyScore,
            double growthScore,
            double lateShare,
            double confidence
    ) {
    }

    private static final class RoleAggregate {
        private final String normalized;
        private final String displayTitle;
        private final String stack;
        private final long sampleCount;
        private final long firstHalfCount;
        private final long secondHalfCount;
        private final long companyCount;
        private final long sourceCount;
        private final double avgQuality;
        private final LinkedHashSet<String> variants;

        private RoleAggregate(
                String normalized,
                String displayTitle,
                String stack,
                long sampleCount,
                long firstHalfCount,
                long secondHalfCount,
                long companyCount,
                long sourceCount,
                double avgQuality,
                LinkedHashSet<String> variants
        ) {
            this.normalized = normalized;
            this.displayTitle = displayTitle;
            this.stack = stack;
            this.sampleCount = sampleCount;
            this.firstHalfCount = firstHalfCount;
            this.secondHalfCount = secondHalfCount;
            this.companyCount = companyCount;
            this.sourceCount = sourceCount;
            this.avgQuality = avgQuality;
            this.variants = variants;
        }

        private RoleAggregate merge(RoleAggregate other) {
            LinkedHashSet<String> mergedVariants = new LinkedHashSet<>(variants);
            mergedVariants.addAll(other.variants);
            String display = sampleCount >= other.sampleCount ? displayTitle : other.displayTitle;
            String dominantStack = sampleCount >= other.sampleCount ? stack : other.stack;
            long total = sampleCount + other.sampleCount;
            double quality = total == 0 ? 0D :
                    (avgQuality * sampleCount + other.avgQuality * other.sampleCount) / total;
            return new RoleAggregate(
                    normalized,
                    display,
                    dominantStack,
                    total,
                    firstHalfCount + other.firstHalfCount,
                    secondHalfCount + other.secondHalfCount,
                    companyCount + other.companyCount,
                    sourceCount + other.sourceCount,
                    quality,
                    mergedVariants
            );
        }
    }
}
