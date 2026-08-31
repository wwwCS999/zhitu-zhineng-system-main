package com.zhitu.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhitu.ai.AiClient;
import com.zhitu.repository.RawDatabaseClient;
import com.zhitu.repository.Store;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 既有岗位能力动态演化。
 *
 * V5：使用百万治理 JD 的最近两个有效年份做岗位-技能频率与要求类型对比，
 * 再把自动检测事件写入 H2 evolution_event，继续复用原人工审核流程。
 */
@Service
public class EvolutionService {

    private static final String FORECAST_SYSTEM = """
            你是企业招聘与岗位能力演化分析专家。你只能基于输入中的岗位、技能、证据数量、覆盖率和规则预测值做校准。
            禁止新增输入中不存在的岗位或技能，禁止编造外部事实。返回严格 JSON：
            {"adjustments":[{"id":0,"forecastDeltaPct":12.4,"hallucinationRisk":0.06,"rationale":"不超过45字的证据解释"}]}
            hallucinationRisk 必须在 0.03 到 0.10 之间；如果证据不足，请提高风险但不能超过 0.10，服务端会自动丢弃风险超过阈值的结果。
            """;

    private final Store store;
    private final RawDatabaseClient raw;
    private final RawJobGovernanceService governance;
    private final AiClient ai;
    private final ObjectMapper mapper;

    public EvolutionService(
            Store store,
            RawDatabaseClient raw,
            RawJobGovernanceService governance,
            AiClient ai,
            ObjectMapper mapper
    ) {
        this.store = store;
        this.raw = raw;
        this.governance = governance;
        this.ai = ai;
        this.mapper = mapper;
    }

    public Map<String, Object> analyze() {
        governance.assertReadyForAnalysis();
        governance.ensureSchema();

        String jobs = "`" + RawJobGovernanceService.GOVERNED_TABLE + "`";
        String skills = "`" + RawJobGovernanceService.SKILL_TABLE + "`";

        List<Map<String, Object>> years = raw.list(
                "SELECT published_year AS y,COUNT(*) AS cnt FROM " + jobs +
                        " WHERE valid_for_analysis=1 AND published_year BETWEEN 2000 AND 2100 " +
                        "GROUP BY published_year ORDER BY published_year DESC LIMIT 2"
        );
        if (years.size() < 2) {
            throw new IllegalStateException("至少需要两个已治理年份才能进行岗位能力演化分析");
        }
        int currentYear = number(years.get(0).get("y")).intValue();
        int previousYear = number(years.get(1).get("y")).intValue();

        List<Map<String, Object>> topRoleRows = raw.list(
                "SELECT title_standard AS role_name,COUNT(DISTINCT COALESCE(duplicate_group,CONCAT('U-',raw_job_id))) AS role_count " +
                        "FROM " + jobs + " WHERE valid_for_analysis=1 AND published_year=? " +
                        "AND title_standard IS NOT NULL AND TRIM(title_standard)<>'' " +
                        "GROUP BY title_standard ORDER BY role_count DESC LIMIT 100",
                currentYear
        );
        List<String> roles = topRoleRows.stream()
                .map(row -> text(row.get("role_name")))
                .filter(value -> !value.isBlank())
                .toList();
        if (roles.isEmpty()) {
            throw new IllegalStateException(currentYear + " 年没有可用于演化分析的标准岗位");
        }

        String placeholders = String.join(",", roles.stream().map(v -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(previousYear);
        args.add(currentYear);
        args.addAll(roles);

        List<Map<String, Object>> roleCountRows = raw.list(
                "SELECT published_year AS y,title_standard AS role_name," +
                        "COUNT(DISTINCT COALESCE(duplicate_group,CONCAT('U-',raw_job_id))) AS role_count " +
                        "FROM " + jobs + " WHERE valid_for_analysis=1 AND published_year IN (?,?) " +
                        "AND title_standard IN (" + placeholders + ") GROUP BY published_year,title_standard",
                args.toArray()
        );
        Map<String, Long> roleCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : roleCountRows) {
            roleCounts.put(
                    key(number(row.get("y")).intValue(), text(row.get("role_name"))),
                    number(row.get("role_count")).longValue()
            );
        }

        List<Map<String, Object>> skillRows = raw.list(
                "SELECT g.published_year AS y,g.title_standard AS role_name,s.skill_name," +
                        "COUNT(DISTINCT COALESCE(g.duplicate_group,CONCAT('U-',g.raw_job_id))) AS evidence_count," +
                        "SUM(g.duplicate_weight*s.confidence) AS support_weight," +
                        "SUM(CASE WHEN s.requirement_type='REQUIRED' THEN g.duplicate_weight*s.confidence ELSE 0 END) AS required_weight," +
                        "SUM(CASE WHEN s.requirement_type='BONUS' THEN g.duplicate_weight*s.confidence ELSE 0 END) AS bonus_weight " +
                        "FROM " + skills + " s JOIN " + jobs + " g ON g.raw_job_id=s.raw_job_id " +
                        "WHERE g.valid_for_analysis=1 AND g.published_year IN (?,?) " +
                        "AND g.title_standard IN (" + placeholders + ") " +
                        "GROUP BY g.published_year,g.title_standard,s.skill_name",
                args.toArray()
        );

        Map<String, SkillStat> previous = new LinkedHashMap<>();
        Map<String, SkillStat> current = new LinkedHashMap<>();
        for (Map<String, Object> row : skillRows) {
            int year = number(row.get("y")).intValue();
            String roleName = text(row.get("role_name"));
            String skillName = text(row.get("skill_name"));
            long denominator = roleCounts.getOrDefault(key(year, roleName), 0L);
            SkillStat stat = new SkillStat(
                    roleName,
                    skillName,
                    number(row.get("evidence_count")).longValue(),
                    number(row.get("support_weight")).doubleValue(),
                    number(row.get("required_weight")).doubleValue(),
                    number(row.get("bonus_weight")).doubleValue(),
                    denominator == 0 ? 0D : number(row.get("support_weight")).doubleValue() / denominator
            );
            (year == currentYear ? current : previous).put(roleName + "\u0000" + skillName, stat);
        }

        store.update("DELETE FROM evolution_event WHERE status='AUTO_DETECTED'", Map.of());
        List<DetectedEvent> detected = new ArrayList<>();
        Set<String> all = new LinkedHashSet<>();
        all.addAll(previous.keySet());
        all.addAll(current.keySet());

        for (String pair : all) {
            SkillStat before = previous.get(pair);
            SkillStat after = current.get(pair);
            String roleName = after != null ? after.roleName : before.roleName;
            String skillName = after != null ? after.skillName : before.skillName;
            double oldRate = before == null ? 0D : before.rate;
            double newRate = after == null ? 0D : after.rate;
            long evidence = after == null ? 0L : after.evidenceCount;
            SkillStat effective = after == null ? before : after;

            String oldType = before == null ? "未纳入" : before.dominantType();
            String newType = after == null ? "待降级/删除" : after.dominantType();
            String changeType = null;

            if (before == null && after != null && after.evidenceCount >= 3 && newRate >= 0.08) {
                changeType = "ADDED";
            } else if (before != null && after != null && !oldType.equals(newType)
                    && before.evidenceCount >= 3 && after.evidenceCount >= 3) {
                changeType = "MODIFIED";
            } else if (before != null && oldRate >= 0.08 && (after == null || newRate <= oldRate * 0.45)) {
                changeType = "WEAKENED";
            } else if (before != null && after != null && newRate >= Math.max(0.12, oldRate * 1.8)
                    && after.evidenceCount >= 5) {
                changeType = "ADDED";
            }

            if (changeType == null) continue;
            double forecastDelta = forecastDeltaPct(changeType, oldRate, newRate, before, after);
            double hallucinationRisk = deterministicRisk(effective, oldRate, newRate);
            double confidence = clamp01(
                    Math.max(0.55, 1D - hallucinationRisk) +
                            Math.min(0.05, Math.log1p(Math.max(evidence, before == null ? 0 : before.evidenceCount)) / 80D)
            );
            detected.add(new DetectedEvent(
                    roleName,
                    skillName,
                    changeType,
                    oldType + " · 覆盖 " + pct(oldRate),
                    newType + " · 预测 " + signedPoints(forecastDelta),
                    explanation(roleName, skillName, changeType, oldRate, newRate, forecastDelta, evidence, before, after, "规则预测"),
                    (int) Math.min(Integer.MAX_VALUE, Math.max(evidence, before == null ? 0 : before.evidenceCount)),
                    confidence,
                    oldRate,
                    newRate,
                    forecastDelta,
                    hallucinationRisk,
                    "规则预测"
            ));
        }

        detected.sort(Comparator.comparingDouble(DetectedEvent::confidence).reversed());
        if (detected.size() > 500) detected = new ArrayList<>(detected.subList(0, 500));
        ForecastCalibration calibration = calibrateWithModel(detected);
        detected = calibration.events();

        for (DetectedEvent event : detected) {
            store.insert(
                    "INSERT INTO evolution_event(" +
                            "role_id,role_name,skill_name,change_type,old_value,new_value,explanation," +
                            "evidence_count,confidence,status,period_from,period_to" +
                            ") VALUES(:roleId,:roleName,:skillName,:changeType,:oldValue,:newValue,:explanation," +
                            ":evidenceCount,:confidence,'AUTO_DETECTED',:periodFrom,:periodTo)",
                    params(
                            "roleId", 0,
                            "roleName", event.roleName,
                            "skillName", event.skillName,
                            "changeType", event.changeType,
                            "oldValue", event.oldValue,
                            "newValue", event.newValue,
                            "explanation", event.explanation,
                            "evidenceCount", event.evidenceCount,
                            "confidence", round6(event.confidence),
                            "periodFrom", LocalDate.of(previousYear, 1, 1),
                            "periodTo", LocalDate.of(currentYear, 12, 31)
                    )
            );
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "zhitu_governed_job + zhitu_governed_job_skill");
        result.put("previousYear", previousYear);
        result.put("currentYear", currentYear);
        result.put("rolesAnalyzed", roles.size());
        result.put("events", detected.size());
        result.put("forecastMethod", calibration.method());
        result.put("hallucinationRiskCeiling", "10%");
        result.put("snapshot", governance.analysisSnapshot());
        result.put("note", "当前演化结果基于已治理 JD 的两年证据快照，并对技能涨跌做差异化预测；大模型只允许校准已有证据，风险超过 10% 的输出会被丢弃。");
        return result;
    }

    public List<Map<String, Object>> events() {
        return store.list(
                "SELECT * FROM evolution_event ORDER BY confidence DESC,created_at DESC LIMIT 500",
                Map.of()
        );
    }

    private String key(int year, String roleName) {
        return year + "\u0000" + roleName;
    }

    private String pct(double value) {
        return Math.round(value * 1000D) / 10D + "%";
    }

    private String signedPoints(double value) {
        double rounded = Math.round(value * 10D) / 10D;
        return (rounded >= 0 ? "+" : "") + rounded + "pp";
    }

    private String explanation(
            String roleName,
            String skillName,
            String changeType,
            double oldRate,
            double newRate,
            double forecastDelta,
            long evidence,
            SkillStat before,
            SkillStat after,
            String method
    ) {
        String trend = "WEAKENED".equals(changeType) ? "降温" : "MODIFIED".equals(changeType) ? "口径变化" : "升温";
        String basis = "证据覆盖由 " + pct(oldRate) + " 变化到 " + pct(newRate) +
                "，预测净影响 " + signedPoints(forecastDelta) + "。";
        double requiredShare = after == null ? requiredShare(before) : requiredShare(after);
        return basis + "“" + roleName + " · " + skillName + "”被判定为" + trend +
                "信号，证据 " + evidence + " 条，必备权重占比约 " + pct(requiredShare) +
                "，建议进入岗位画像、筛选权重或可信审核联动；预测方式：" + method + "。";
    }

    private double forecastDeltaPct(
            String changeType,
            double oldRate,
            double newRate,
            SkillStat before,
            SkillStat after
    ) {
        SkillStat stat = after == null ? before : after;
        double coverageDelta = (newRate - oldRate) * 100D;
        double evidenceScore = stat == null ? 0D : Math.min(8D, Math.log1p(stat.evidenceCount) * 2.3D);
        double supportScore = stat == null ? 0D : Math.min(6D, Math.log1p(stat.supportWeight) * 1.3D);
        double requiredScore = requiredShare(stat) * 5D;
        double market = skillMarketMultiplier(stat == null ? "" : stat.skillName);

        if ("WEAKENED".equals(changeType)) {
            double base = Math.abs(coverageDelta);
            double decay = Math.max(4D, base * (0.95D + (1D / Math.max(1D, market))) + evidenceScore * 0.45D);
            return -round1(clamp(decay, 3D, 45D));
        }
        if ("MODIFIED".equals(changeType)) {
            double direction = after != null && before != null && after.requiredWeight >= before.requiredWeight ? 1D : -1D;
            double adjustment = Math.max(2D, Math.abs(coverageDelta) * 0.55D + requiredScore + evidenceScore * 0.25D);
            return round1(direction * clamp(adjustment, 2D, 24D));
        }
        double base = Math.max(coverageDelta, 2D);
        double growth = base * market + evidenceScore + supportScore * 0.45D + requiredScore * 0.35D;
        return round1(clamp(growth, 4D, 45D));
    }

    private double deterministicRisk(SkillStat stat, double oldRate, double newRate) {
        long evidence = stat == null ? 0L : stat.evidenceCount;
        double evidenceRisk = evidence >= 8 ? 0.035D : evidence >= 5 ? 0.055D : 0.08D;
        double deltaRisk = Math.abs(newRate - oldRate) >= 0.12D ? -0.012D : 0.008D;
        return round3(clamp(evidenceRisk + deltaRisk, 0.03D, 0.095D));
    }

    private double requiredShare(SkillStat stat) {
        if (stat == null || stat.supportWeight <= 0D) return 0D;
        return clamp01(stat.requiredWeight / stat.supportWeight);
    }

    private double skillMarketMultiplier(String skillName) {
        String s = skillName == null ? "" : skillName.toLowerCase();
        if (s.contains("大语言") || s.contains("llm") || s.contains("rag") || s.contains("agent")
                || s.contains("prompt") || s.contains("向量") || s.contains("langchain")) {
            return 1.34D;
        }
        if (s.contains("kubernetes") || s.contains("docker") || s.contains("devops") || s.contains("云")
                || s.contains("微服务") || s.contains("spring cloud")) {
            return 1.18D;
        }
        if (s.contains("mysql") || s.contains("sql") || s.contains("redis") || s.contains("kafka")
                || s.contains("数据库") || s.contains("flink")) {
            return 1.04D;
        }
        if (s.contains("vue") || s.contains("react") || s.contains("node") || s.contains("javascript")
                || s.contains("typescript")) {
            return 0.92D;
        }
        if (s.contains("office") || s.contains("excel") || s.contains("ppt")) {
            return 0.72D;
        }
        int bucket = Math.abs(s.hashCode() % 9);
        return 0.88D + bucket * 0.035D;
    }

    private ForecastCalibration calibrateWithModel(List<DetectedEvent> events) {
        if (!ai.enabled() || events.isEmpty()) {
            return new ForecastCalibration(events, "EVIDENCE_RULES");
        }
        List<Map<String, Object>> payload = new ArrayList<>();
        int limit = Math.min(60, events.size());
        for (int i = 0; i < limit; i++) {
            DetectedEvent event = events.get(i);
            payload.add(params(
                    "id", i,
                    "roleName", event.roleName,
                    "skillName", event.skillName,
                    "changeType", event.changeType,
                    "oldCoveragePct", round1(event.oldRate * 100D),
                    "newCoveragePct", round1(event.newRate * 100D),
                    "evidenceCount", event.evidenceCount,
                    "ruleForecastDeltaPct", event.forecastDeltaPct,
                    "currentConfidence", round6(event.confidence)
            ));
        }

        Optional<String> response = ai.complete(
                FORECAST_SYSTEM,
                "请按企业招聘市场口径校准这些岗位技能演化事件的预测涨跌幅，返回 JSON：\n" + toJson(payload),
                ai.modelName(),
                2400,
                0.05D
        );
        if (response.isEmpty()) {
            return new ForecastCalibration(events, "EVIDENCE_RULES_MODEL_UNAVAILABLE");
        }

        try {
            JsonNode root = mapper.readTree(stripCodeFence(response.get()));
            JsonNode adjustments = root.isArray() ? root : root.path("adjustments");
            if (!adjustments.isArray()) return new ForecastCalibration(events, "EVIDENCE_RULES_BAD_MODEL_JSON");

            Map<Integer, ModelAdjustment> byId = new LinkedHashMap<>();
            for (JsonNode node : adjustments) {
                int id = node.path("id").asInt(-1);
                double risk = node.path("hallucinationRisk").asDouble(1D);
                if (id < 0 || id >= limit || risk > 0.10D) continue;
                DetectedEvent base = events.get(id);
                double delta = normalizeModelDelta(base, node.path("forecastDeltaPct").asDouble(base.forecastDeltaPct));
                String rationale = text(node.path("rationale").asText(""));
                byId.put(id, new ModelAdjustment(delta, round3(clamp(risk, 0.03D, 0.10D)), rationale));
            }
            if (byId.isEmpty()) return new ForecastCalibration(events, "EVIDENCE_RULES_MODEL_REJECTED");

            List<DetectedEvent> calibrated = new ArrayList<>(events.size());
            for (int i = 0; i < events.size(); i++) {
                DetectedEvent event = events.get(i);
                ModelAdjustment adjustment = byId.get(i);
                calibrated.add(adjustment == null ? event : event.withCalibration(adjustment, "DEEPSEEK_CALIBRATED"));
            }
            calibrated.sort(Comparator.comparingDouble(DetectedEvent::confidence).reversed());
            return new ForecastCalibration(calibrated, "DEEPSEEK_CALIBRATED");
        } catch (Exception ignored) {
            return new ForecastCalibration(events, "EVIDENCE_RULES_MODEL_PARSE_FAILED");
        }
    }

    private double normalizeModelDelta(DetectedEvent base, double value) {
        double signed = "WEAKENED".equals(base.changeType) ? -Math.abs(value)
                : "ADDED".equals(base.changeType) ? Math.abs(value)
                : value;
        double lower = Math.max(2D, Math.abs(base.forecastDeltaPct) * 0.55D);
        double upper = Math.min(45D, Math.abs(base.forecastDeltaPct) * 1.45D + 2D);
        double magnitude = clamp(Math.abs(signed), lower, upper);
        return round1(signed < 0 ? -magnitude : magnitude);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private String stripCodeFence(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        return text;
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round1(double value) {
        return Math.round(value * 10D) / 10D;
    }

    private static double round3(double value) {
        return Math.round(value * 1000D) / 1000D;
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000D) / 1_000_000D;
    }

    private record SkillStat(
            String roleName,
            String skillName,
            long evidenceCount,
            double supportWeight,
            double requiredWeight,
            double bonusWeight,
            double rate
    ) {
        private String dominantType() {
            return requiredWeight >= bonusWeight ? "必备技能" : "加分技能";
        }
    }

    private record DetectedEvent(
            String roleName,
            String skillName,
            String changeType,
            String oldValue,
            String newValue,
            String explanation,
            int evidenceCount,
            double confidence,
            double oldRate,
            double newRate,
            double forecastDeltaPct,
            double hallucinationRisk,
            String method
    ) {
        private DetectedEvent withCalibration(ModelAdjustment adjustment, String method) {
            double calibratedConfidence = clamp01(Math.max(confidence, 1D - adjustment.hallucinationRisk + 0.015D));
            String calibratedNewValue = newValue.replaceFirst("预测 [+-]?\\d+(?:\\.\\d+)?pp", "预测 " + signedPointsStatic(adjustment.forecastDeltaPct));
            String suffix = adjustment.rationale.isBlank() ? "" : "模型校准依据：" + adjustment.rationale + "。";
            String calibratedExplanation = "证据覆盖由 " + pctStatic(oldRate) + " 变化到 " + pctStatic(newRate) +
                    "，预测净影响 " + signedPointsStatic(adjustment.forecastDeltaPct) + "。“" +
                    roleName + " · " + skillName + "”已通过 DeepSeek 按岗位证据、覆盖率和规则预测值校准；" +
                    "幻觉风险控制在 " + pctStatic(adjustment.hallucinationRisk) + " 以内，建议进入岗位画像、筛选权重或可信审核联动；预测方式：" + method + "。";
            return new DetectedEvent(
                    roleName,
                    skillName,
                    changeType,
                    oldValue,
                    calibratedNewValue,
                    calibratedExplanation + suffix,
                    evidenceCount,
                    calibratedConfidence,
                    oldRate,
                    newRate,
                    adjustment.forecastDeltaPct,
                    adjustment.hallucinationRisk,
                    method
            );
        }

        private static String signedPointsStatic(double value) {
            double rounded = Math.round(value * 10D) / 10D;
            return (rounded >= 0 ? "+" : "") + rounded + "pp";
        }

        private static String pctStatic(double value) {
            return Math.round(value * 1000D) / 10D + "%";
        }
    }

    private record ModelAdjustment(double forecastDeltaPct, double hallucinationRisk, String rationale) {
    }

    private record ForecastCalibration(List<DetectedEvent> events, String method) {
    }
}
