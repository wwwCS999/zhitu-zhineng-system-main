package com.zhitu.service;

import com.zhitu.repository.RawDatabaseClient;
import com.zhitu.repository.Store;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 总览页数据服务（百万 JD 快照优化版）。
 *
 * 优化原则：
 * 1. 已治理数量、有效数量、原始总量直接读取 zhitu_governance_run 快照，
 *    不再为了刷新总览反复 COUNT 百万表；
 * 2. 技术栈 + 年份一次 GROUP BY 后在 Java 内同时生成两个图表，减少一次全表扫描；
 * 3. 技能数量、技能关系数、高频技能由一次技能 GROUP BY 同时得到，
 *    不再先 COUNT/JOIN 再做第二次技能聚合；
 * 4. 最近一次完整总览保存在内存中。页面第一次进入可以优先显示缓存；
 *    用户点击“刷新”才重新聚合当前快照；
 * 5. 聚合失败时，如果存在上一份成功缓存，则返回旧缓存并标记 stale，
 *    不再直接让前端出现 30 秒超时空白页。
 */
@Service
public class DashboardService {

    private final Store store;
    private final RawDatabaseClient raw;
    private final RawJobGovernanceService governance;

    private final Object cacheLock = new Object();
    private volatile DashboardCache cache;

    public DashboardService(
            Store store,
            RawDatabaseClient raw,
            RawJobGovernanceService governance
    ) {
        this.store = store;
        this.raw = raw;
        this.governance = governance;
    }

    /**
     * 保持旧调用兼容。
     */
    public Map<String, Object> overview() {
        return overview(false);
    }

    /**
     * @param refresh true 时强制基于当前治理快照重新聚合；false 时优先返回最近缓存。
     */
    public Map<String, Object> overview(boolean refresh) {
        if (!raw.ping() || !raw.tableExists(RawJobGovernanceService.GOVERNED_TABLE)) {
            return legacyOverview();
        }

        Map<String, Object> snapshot;
        try {
            snapshot = governance.progressSnapshot();
        } catch (Exception e) {
            DashboardCache current = cache;
            if (current != null) {
                return attachRuntimeState(current.payload(), Map.of(), true, rootMessage(e));
            }
            return legacyOverview();
        }

        DashboardCache current = cache;

        // 页面正常进入时：有成功缓存就立即返回，治理后台继续跑也不会让页面等待百万聚合。
        if (!refresh && current != null) {
            return attachRuntimeState(current.payload(), snapshot, false, "");
        }

        synchronized (cacheLock) {
            // 双重检查，避免并发点击刷新时重复执行百万聚合。
            current = cache;
            if (!refresh && current != null) {
                return attachRuntimeState(current.payload(), snapshot, false, "");
            }

            try {
                Map<String, Object> rebuilt = governedOverview(snapshot);
                long snapshotVersion = number(snapshot.get("processedCount")).longValue();
                cache = new DashboardCache(
                        snapshotVersion,
                        Instant.now().toString(),
                        rebuilt
                );
                return attachRuntimeState(rebuilt, snapshot, false, "");
            } catch (Exception e) {
                current = cache;
                if (current != null) {
                    return attachRuntimeState(
                            current.payload(),
                            snapshot,
                            true,
                            "当前快照聚合较慢，已展示最近一次成功结果：" + rootMessage(e)
                    );
                }
                throw e;
            }
        }
    }

    private Map<String, Object> governedOverview(Map<String, Object> snapshot) {
        governance.ensureSchema();

        String jobs = "`" + RawJobGovernanceService.GOVERNED_TABLE + "`";
        String skills = "`" + RawJobGovernanceService.SKILL_TABLE + "`";

        long rawTotal = number(snapshot.get("rawTotalApprox")).longValue();
        long governedRows = number(snapshot.get("processedCount")).longValue();
        long validRows = number(snapshot.get("validCount")).longValue();

        /*
         * 查询 1：岗位质量 + 标准岗位数量。
         * 只扫描岗位表一次。
         */
        Map<String, Object> jobAggregate = raw.one(
                "SELECT " +
                        "COUNT(DISTINCT CASE WHEN valid_for_analysis=1 AND COALESCE(is_deleted,0)=0 " +
                        "THEN NULLIF(title_standard,'') END) AS role_count," +
                        "COALESCE(AVG(CASE WHEN COALESCE(is_deleted,0)=0 THEN quality_score END),0) AS avg_quality," +
                        "COALESCE(AVG(CASE WHEN COALESCE(is_deleted,0)=0 THEN stale_score END),0) AS avg_stale," +
                        "COALESCE(SUM(CASE WHEN duplicate_group IS NOT NULL AND COALESCE(is_deleted,0)=0 THEN 1 ELSE 0 END),0) AS duplicates " +
                        "FROM " + jobs
        );

        long roleCount = number(jobAggregate.get("role_count")).longValue();

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("avg_quality", jobAggregate.getOrDefault("avg_quality", 0));
        quality.put("avg_stale", jobAggregate.getOrDefault("avg_stale", 0));
        quality.put("duplicates", jobAggregate.getOrDefault("duplicates", 0));
        quality.put("valid_rows", validRows);

        /*
         * 查询 2：技术栈 + 年份联合聚合。
         * 一次查询同时生成技术栈分布和年度趋势。
         */
        List<Map<String, Object>> stackYearRows = raw.list(
                "SELECT " +
                        "COALESCE(NULLIF(tech_stack,''),'未分类') AS stack_name," +
                        "published_year AS published_year," +
                        "COUNT(*) AS metric_value " +
                        "FROM " + jobs + " " +
                        "WHERE valid_for_analysis=1 AND COALESCE(is_deleted,0)=0 " +
                        "GROUP BY COALESCE(NULLIF(tech_stack,''),'未分类'),published_year"
        );

        Map<String, Long> stackCounts = new LinkedHashMap<>();
        Map<Integer, Long> yearCounts = new LinkedHashMap<>();

        for (Map<String, Object> row : stackYearRows) {
            String stackName = text(row.get("stack_name"));
            if (stackName.isBlank()) {
                stackName = "未分类";
            }

            long count = number(row.get("metric_value")).longValue();
            stackCounts.merge(stackName, count, Long::sum);

            int year = number(row.get("published_year")).intValue();
            if (year >= 2000 && year <= 2100) {
                yearCounts.merge(year, count, Long::sum);
            }
        }

        List<Map<String, Object>> stacks = stackCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(12)
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", entry.getKey());
                    item.put("value", entry.getValue());
                    return item;
                })
                .toList();

        List<Map<String, Object>> trend = yearCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("day", String.valueOf(entry.getKey()));
                    item.put("value", entry.getValue());
                    return item;
                })
                .toList();

        /*
         * 查询 3：一次技能 GROUP BY 同时得到：
         * - distinct skill 数量（结果行数）
         * - relation 总数（各 skill relation_count 求和）
         * - Top 技能证据强度
         *
         * STRAIGHT_JOIN 强制从已过滤的岗位表向 skill.raw_job_id 索引做连接，
         * 避免优化器从超大的技能表反向扫描。
         */
        List<Map<String, Object>> skillRows = raw.list(
                "SELECT " +
                        "s.skill_name AS skill_name," +
                        "COUNT(*) AS relation_count," +
                        "ROUND(SUM(COALESCE(g.duplicate_weight,1) * COALESCE(s.confidence,0)),2) AS metric_value," +
                        "AVG(COALESCE(s.confidence,0)) AS avg_confidence " +
                        "FROM " + jobs + " g " +
                        "STRAIGHT_JOIN " + skills + " s ON s.raw_job_id=g.raw_job_id " +
                        "WHERE g.valid_for_analysis=1 AND COALESCE(g.is_deleted,0)=0 " +
                        "GROUP BY s.skill_name " +
                        "ORDER BY metric_value DESC"
        );

        long skillCount = skillRows.size();
        long relationCount = 0L;
        List<Map<String, Object>> topSkills = new ArrayList<>();

        for (int i = 0; i < skillRows.size(); i++) {
            Map<String, Object> row = skillRows.get(i);
            relationCount += number(row.get("relation_count")).longValue();

            if (i < 12) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", row.get("skill_name"));
                item.put("value", row.get("metric_value"));
                item.put("confidence", row.get("avg_confidence"));
                topSkills.add(item);
            }
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("documents", rawTotal);
        metrics.put("jobs", governedRows);
        metrics.put("validJobs", validRows);
        metrics.put("roles", roleCount);
        metrics.put("skills", skillCount);
        metrics.put("relations", relationCount);
        metrics.put("emerging", store.count("emerging_candidate"));
        metrics.put("audits", store.count("audit_record"));
        metrics.put("matches", store.count("match_report"));

        long pending = (long) store.scalarDouble(
                "SELECT COUNT(*) FROM emerging_candidate WHERE status='AUTO_CANDIDATE'",
                Map.of()
        );
        pending += (long) store.scalarDouble(
                "SELECT COUNT(*) FROM evolution_event WHERE status='AUTO_DETECTED'",
                Map.of()
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataSource", "MYSQL_GOVERNED_MILLION_JD");
        result.put("metrics", metrics);
        result.put("quality", quality);
        result.put("stacks", stacks);
        result.put("topSkills", topSkills);
        result.put("trend", trend);
        result.put("agents", agentStatus());
        result.put("pendingAudits", pending);
        result.put("governance", snapshot);
        result.put("snapshotVersion", governedRows);
        result.put("generatedAt", Instant.now().toString());
        result.put("stale", false);
        return result;
    }

    /**
     * 使用缓存分析结果，但把治理进度替换成实时轻量快照。
     * 这样后台每 100 条继续解析时，总览顶部“已治理岗位”仍能更新，
     * 同时图表不会每 100 条自动重新扫描几十万数据。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> attachRuntimeState(
            Map<String, Object> payload,
            Map<String, Object> snapshot,
            boolean stale,
            String warning
    ) {
        Map<String, Object> result = deepTopLevelCopy(payload);

        if (!snapshot.isEmpty()) {
            long processed = number(snapshot.get("processedCount")).longValue();
            long valid = number(snapshot.get("validCount")).longValue();
            long rawTotal = number(snapshot.get("rawTotalApprox")).longValue();

            Object metricsObject = result.get("metrics");
            if (metricsObject instanceof Map<?, ?> sourceMetrics) {
                Map<String, Object> metrics = new LinkedHashMap<>();
                sourceMetrics.forEach((key, value) -> metrics.put(String.valueOf(key), value));
                metrics.put("documents", rawTotal);
                metrics.put("jobs", processed);
                metrics.put("validJobs", valid);
                result.put("metrics", metrics);
            }

            result.put("governance", snapshot);
            result.put("currentSnapshotVersion", processed);
        }

        DashboardCache current = cache;
        if (current != null) {
            result.put("analysisSnapshotVersion", current.snapshotVersion());
            result.put("generatedAt", current.generatedAt());
        }

        result.put("stale", stale);
        if (warning != null && !warning.isBlank()) {
            result.put("warning", warning);
        }
        return result;
    }

    private Map<String, Object> deepTopLevelCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> mapValue) {
                Map<String, Object> child = new LinkedHashMap<>();
                mapValue.forEach((k, v) -> child.put(String.valueOf(k), v));
                copy.put(entry.getKey(), child);
            } else if (value instanceof List<?> listValue) {
                copy.put(entry.getKey(), new ArrayList<>(listValue));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }

    private Map<String, Object> legacyOverview() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("documents", store.count("source_document"));
        metrics.put("jobs", store.count("job_posting"));
        metrics.put("validJobs", store.count("job_posting"));
        metrics.put("roles", store.count("job_role"));
        metrics.put("skills", store.count("skill"));
        metrics.put("relations", store.count("role_skill"));
        metrics.put("emerging", store.count("emerging_candidate"));
        metrics.put("audits", store.count("audit_record"));
        metrics.put("matches", store.count("match_report"));

        Map<String, Object> quality = store.one(
                "SELECT COALESCE(AVG(quality_score),0) avg_quality," +
                        "COALESCE(AVG(stale_score),0) avg_stale," +
                        "SUM(CASE WHEN duplicate_group IS NOT NULL THEN 1 ELSE 0 END) duplicates " +
                        "FROM source_document",
                Map.of()
        );

        List<Map<String, Object>> stacks = new ArrayList<>();
        for (Map<String, Object> row : store.list(
                "SELECT tech_stack AS stack_name,COUNT(*) AS metric_value " +
                        "FROM job_role GROUP BY tech_stack ORDER BY COUNT(*) DESC",
                Map.of()
        )) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.get("stack_name") == null ? "未分类" : row.get("stack_name"));
            item.put("value", row.get("metric_value") == null ? 0 : row.get("metric_value"));
            stacks.add(item);
        }

        List<Map<String, Object>> topSkills = new ArrayList<>();
        for (Map<String, Object> row : store.list(
                "SELECT s.canonical_name AS skill_name,COUNT(*) AS metric_value,AVG(rs.confidence) AS avg_confidence " +
                        "FROM role_skill rs JOIN skill s ON s.id=rs.skill_id " +
                        "GROUP BY s.canonical_name ORDER BY COUNT(*) DESC LIMIT 12",
                Map.of()
        )) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.get("skill_name"));
            item.put("value", row.get("metric_value"));
            item.put("confidence", row.get("avg_confidence"));
            topSkills.add(item);
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        for (Map<String, Object> row : store.list(
                "SELECT posted_at AS date_key,COUNT(*) AS metric_value FROM job_posting " +
                        "GROUP BY posted_at ORDER BY posted_at DESC LIMIT 30",
                Map.of()
        )) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day", row.get("date_key") == null ? "" : row.get("date_key"));
            item.put("value", row.get("metric_value") == null ? 0 : row.get("metric_value"));
            trend.add(0, item);
        }

        long pending = (long) store.scalarDouble(
                "SELECT COUNT(*) FROM emerging_candidate WHERE status='AUTO_CANDIDATE'",
                Map.of()
        );
        pending += (long) store.scalarDouble(
                "SELECT COUNT(*) FROM evolution_event WHERE status='AUTO_DETECTED'",
                Map.of()
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataSource", "H2_DEMO_FALLBACK");
        result.put("metrics", metrics);
        result.put("quality", quality);
        result.put("stacks", stacks);
        result.put("topSkills", topSkills);
        result.put("trend", trend);
        result.put("agents", agentStatus());
        result.put("pendingAudits", pending);
        result.put("stale", false);
        return result;
    }

    private List<Map<String, Object>> agentStatus() {
        List<Map<String, Object>> agents = new ArrayList<>();
        for (String agentName : List.of(
                "数据治理智能体",
                "岗位洞察智能体",
                "能力图谱与演化智能体",
                "画像匹配智能体",
                "学习规划智能体",
                "可信审核智能体"
        )) {
            Optional<Map<String, Object>> last = store.maybe(
                    "SELECT status,created_at,duration_ms FROM agent_run " +
                            "WHERE agent_name=:agentName ORDER BY id DESC LIMIT 1",
                    Map.of("agentName", agentName)
            );
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", agentName);
            item.put("status", last.map(v -> v.get("status")).orElse("READY"));
            item.put("lastRun", last.map(v -> v.get("created_at")).orElse(null));
            item.put("durationMs", last.map(v -> v.get("duration_ms")).orElse(0));
            agents.add(item);
        }
        return agents;
    }

    private Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank()
                ? cursor.getClass().getSimpleName()
                : message;
    }

    private record DashboardCache(
            long snapshotVersion,
            String generatedAt,
            Map<String, Object> payload
    ) {
    }
}
