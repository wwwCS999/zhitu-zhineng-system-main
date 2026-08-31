package com.zhitu.service;

import com.zhitu.common.Jsons;
import com.zhitu.common.TextUtils;
import com.zhitu.engine.MatchingScoreEngine;
import com.zhitu.repository.RawDatabaseClient;
import com.zhitu.repository.Store;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户画像与岗位匹配。
 *
 * V10：目标岗位目录仍保留在 H2 job_role 中以维持稳定 roleId，
 * 但岗位技能要求在每次执行匹配时都从“当前已治理 MySQL 快照”重新聚合。
 * 因此解析数据每增加 100 条后，只需要刷新岗位列表并重新执行匹配，
 * 就会使用最新的岗位技能证据，不必等待百万治理全部完成。
 */
@Service
public class MatchingService {

    private final Store store;
    private final MatchingScoreEngine engine;
    private final RawDatabaseClient raw;
    private final RawJobGovernanceService governance;
    private final Jsons jsons;

    public MatchingService(
            Store store,
            MatchingScoreEngine engine,
            RawDatabaseClient raw,
            RawJobGovernanceService governance,
            Jsons jsons
    ) {
        this.store = store;
        this.engine = engine;
        this.raw = raw;
        this.governance = governance;
        this.jsons = jsons;
    }

    public Map<String, Object> analyze(long resumeId, long roleId) {
        Map<String, Object> resume = store.one(
                "SELECT * FROM resume_profile WHERE id=:id",
                Map.of("id", resumeId)
        );
        Map<String, Object> role = store.one(
                "SELECT * FROM job_role WHERE id=:id",
                Map.of("id", roleId)
        );

        Map<String, Double> required = new LinkedHashMap<>();
        Map<String, Double> bonus = new LinkedHashMap<>();

        boolean snapshotUsed = false;
        try {
            governance.assertReadyForAnalysis();
            RequirementSnapshot snapshot = snapshotRequirements(String.valueOf(role.get("role_name")));
            required.putAll(snapshot.required());
            bonus.putAll(snapshot.bonus());
            snapshotUsed = !required.isEmpty() || !bonus.isEmpty();
        } catch (Exception ignored) {
            // 当前快照不足或数据库临时不可用时，仍可回退到 H2 已发布岗位技能关系。
        }

        if (!snapshotUsed) {
            loadLegacyRequirements(roleId, required, bonus);
        }

        Set<String> skills = new LinkedHashSet<>(
                TextUtils.jsonList(String.valueOf(resume.get("skills")))
        );
        String projects = String.valueOf(resume.get("projects"));
        double years = ((Number) resume.get("experience_years")).doubleValue();

        List<Map<String, Object>> internships = jsons.listOfMaps(String.valueOf(resume.get("internships")));
        int internshipCount = internships.size();
        int relevantInternships = 0;
        for (Map<String, Object> it : internships) {
            String text = String.valueOf(it.getOrDefault("role", "")) + " " + String.valueOf(it.getOrDefault("description", ""));
            for (String sk : required.keySet()) {
                if (!sk.isBlank() && text.toLowerCase().contains(sk.toLowerCase())) {
                    relevantInternships++;
                    break;
                }
            }
        }

        MatchingScoreEngine.Result score = engine.score(
                skills,
                required,
                bonus,
                projects,
                years,
                String.valueOf(role.get("level_name")),
                String.valueOf(resume.get("education")),
                internshipCount,
                relevantInternships
        );

        List<String> suggestions = new ArrayList<>();
        for (String skill : score.missing().stream().limit(5).toList()) {
            suggestions.add("优先补齐「" + skill + "」，并产出可验证项目证据");
        }
        if (score.project() < 75) {
            suggestions.add("围绕目标岗位场景补充端到端项目和量化结果");
        }
        if (score.level() < 75) {
            suggestions.add("先匹配相邻等级岗位，逐步积累复杂系统经验");
        }

        Map<String, Object> snapshotMeta;
        try {
            snapshotMeta = governance.analysisSnapshot();
        } catch (Exception ignored) {
            snapshotMeta = Map.of();
        }

        String explanation = snapshotUsed
                ? "综合分由技能40%、实习18%、项目15%、技术栈12%、岗位等级9%、学历6%加权计算；岗位技能要求已在本次匹配前从当前治理快照重新聚合。"
                : "综合分由技能40%、实习18%、项目15%、技术栈12%、岗位等级9%、学历6%加权计算；当前使用已发布岗位技能目录作为回退数据。";

        long id = store.insert(
                "INSERT INTO match_report(" +
                        "resume_id,role_id,overall_score,skill_score,internship_score,project_score,stack_score,level_score,education_score," +
                        "matched_skills,missing_skills,suggestions,explanation" +
                        ") VALUES(:re,:ro,:o,:s,:i,:p,:st,:l,:e,:m,:mi,:sg,:x)",
                params(
                        "re", resumeId,
                        "ro", roleId,
                        "o", score.overall(),
                        "s", score.skill(),
                        "i", score.internship(),
                        "p", score.project(),
                        "st", score.stack(),
                        "l", score.level(),
                        "e", score.education(),
                        "m", TextUtils.jsonArray(score.matched()),
                        "mi", TextUtils.jsonArray(score.missing()),
                        "sg", TextUtils.jsonArray(suggestions),
                        "x", explanation
                )
        );

        Map<String, Object> report = report(id);
        report.put("snapshotUsed", snapshotUsed);
        report.put("snapshot", snapshotMeta);
        return report;
    }

    private RequirementSnapshot snapshotRequirements(String roleName) {
        governance.ensureSchema();

        String jobs = "`" + RawJobGovernanceService.GOVERNED_TABLE + "`";
        String skills = "`" + RawJobGovernanceService.SKILL_TABLE + "`";

        List<Map<String, Object>> rows = raw.list(
                "SELECT s.skill_name," +
                        "SUM(COALESCE(g.duplicate_weight,1) * COALESCE(s.confidence,0)) AS support_weight," +
                        "SUM(CASE WHEN UPPER(COALESCE(s.requirement_type,''))='REQUIRED' " +
                        "THEN COALESCE(g.duplicate_weight,1) * COALESCE(s.confidence,0) ELSE 0 END) AS required_weight," +
                        "SUM(CASE WHEN UPPER(COALESCE(s.requirement_type,'')) IN ('BONUS','PREFERRED') " +
                        "THEN COALESCE(g.duplicate_weight,1) * COALESCE(s.confidence,0) ELSE 0 END) AS bonus_weight," +
                        "COUNT(DISTINCT COALESCE(g.duplicate_group,CONCAT('U-',g.raw_job_id))) AS evidence_count " +
                        "FROM " + skills + " s JOIN " + jobs + " g ON g.raw_job_id=s.raw_job_id " +
                        "WHERE g.valid_for_analysis=1 AND COALESCE(g.is_deleted,0)=0 " +
                        "AND g.title_standard=? " +
                        "GROUP BY s.skill_name " +
                        "ORDER BY support_weight DESC LIMIT 60",
                roleName
        );

        double maxSupport = rows.stream()
                .mapToDouble(row -> number(row.get("support_weight")))
                .max()
                .orElse(1D);
        if (maxSupport <= 0D) {
            maxSupport = 1D;
        }

        Map<String, Double> required = new LinkedHashMap<>();
        Map<String, Double> bonus = new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            String skillName = text(row.get("skill_name"));
            if (skillName.isBlank()) {
                continue;
            }

            double support = number(row.get("support_weight"));
            double requiredWeight = number(row.get("required_weight"));
            double bonusWeight = number(row.get("bonus_weight"));
            double importance = Math.max(0.08D, Math.min(1D, support / maxSupport));

            if (requiredWeight > 0D && requiredWeight >= bonusWeight) {
                required.put(skillName, importance);
            } else if (bonusWeight > 0D) {
                bonus.put(skillName, importance);
            } else {
                // 未明确标注必备/加分的高频技能按一般必备能力处理，但给予较低权重。
                required.put(skillName, Math.max(0.08D, importance * 0.72D));
            }
        }

        return new RequirementSnapshot(required, bonus);
    }

    private void loadLegacyRequirements(
            long roleId,
            Map<String, Double> required,
            Map<String, Double> bonus
    ) {
        List<Map<String, Object>> relations = store.list(
                "SELECT rs.requirement_type,rs.importance,s.canonical_name " +
                        "FROM role_skill rs JOIN skill s ON s.id=rs.skill_id " +
                        "WHERE rs.role_id=:r AND rs.status='PUBLISHED'",
                Map.of("r", roleId)
        );

        for (Map<String, Object> relation : relations) {
            String name = String.valueOf(relation.get("canonical_name"));
            double weight = ((Number) relation.get("importance")).doubleValue();
            if ("REQUIRED".equals(relation.get("requirement_type"))) {
                required.put(name, weight);
            } else {
                bonus.put(name, weight);
            }
        }
    }

    public Map<String, Object> report(long id) {
        Map<String, Object> result = store.one(
                "SELECT m.*,jr.role_name,jr.tech_stack,jr.level_name,rp.person_name " +
                        "FROM match_report m " +
                        "JOIN job_role jr ON jr.id=m.role_id " +
                        "JOIN resume_profile rp ON rp.id=m.resume_id " +
                        "WHERE m.id=:id",
                Map.of("id", id)
        );
        result.put("matchedSkills", TextUtils.jsonList(String.valueOf(result.get("matched_skills"))));
        result.put("missingSkills", TextUtils.jsonList(String.valueOf(result.get("missing_skills"))));
        result.put("suggestionList", TextUtils.jsonList(String.valueOf(result.get("suggestions"))));
        Map<String, Object> dimensions = new LinkedHashMap<>();
        dimensions.put("技能", result.get("skill_score"));
        dimensions.put("实习", result.get("internship_score"));
        dimensions.put("项目", result.get("project_score"));
        dimensions.put("技术栈", result.get("stack_score"));
        dimensions.put("岗位等级", result.get("level_score"));
        dimensions.put("学历", result.get("education_score"));
        result.put("dimensions", dimensions);
        return result;
    }

    public List<Map<String, Object>> reports() {
        return store.list(
                "SELECT m.id,m.overall_score,m.skill_score,m.created_at,r.role_name,p.person_name " +
                        "FROM match_report m " +
                        "JOIN job_role r ON r.id=m.role_id " +
                        "JOIN resume_profile p ON p.id=m.resume_id " +
                        "ORDER BY m.id DESC",
                Map.of()
        );
    }

    private double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0D;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0D;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Map<String, Object> params(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private record RequirementSnapshot(
            Map<String, Double> required,
            Map<String, Double> bonus
    ) {
    }
}
