package com.zhitu.service;

import com.zhitu.repository.RawDatabaseClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 全量岗位能力母图构建器（V14：ONLY_FULL_GROUP_BY 兼容版）。
 *
 * V12 的问题：
 * - 虽然前端请求已经改成异步，但后台仍然用一条超大型 JOIN + GROUP BY SQL
 *   对几十万/百万治理 JD 和技能关系一次性聚合；
 * - 当数据量增大后，该单条 SQL 仍可能命中 JDBC / MySQL socket / statement timeout，
 *   从而出现 "Statement cancelled due to timeout or client request"。
 *
 * V13 的修复：
 * 1. 不再用一条 SQL 聚合全部百万数据；
 * 2. 按 raw_job_id 范围分批扫描，每批默认 10,000 个 ID；
 * 3. 每批结果增量写入两个轻量工作聚合表；
 * 4. 如果某一批仍发生瞬时超时，会自动重试；连续失败则自动把范围二分后继续；
 * 5. 最终只从已经压缩后的工作表读取 Top N 聚合关系，生成 master-graph.json；
 * 6. 构建失败时旧母图仍然保留，不会被删除。
 */
@Service
public class GraphSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(GraphSnapshotService.class);

    private static final List<String> GRAPH_PALETTE = List.of(
            "#BFD7D2",
            "#51999F",
            "#4198AC",
            "#79C0CD",
            "#D9CB92",
            "#ECBC66",
            "#E49E58",
            "#ED805A"
    );

    private static final String ROLE_WORK_TABLE = "zhitu_graph_role_agg_work";
    private static final String EDGE_WORK_TABLE = "zhitu_graph_edge_agg_work";

    private final RawDatabaseClient raw;
    private final RawJobGovernanceService governance;
    private final GraphMasterStore masterStore;

    private final int maxMasterEdges;
    private final int finalQueryTimeoutSeconds;
    private final int batchQueryTimeoutSeconds;
    private final long batchIdSpan;
    private final long minimumSplitSpan;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "zhitu-master-graph-builder");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean building = new AtomicBoolean(false);
    private final AtomicReference<Map<String, Object>> buildState =
            new AtomicReference<>(idleState("IDLE", "等待更新全量母图"));

    public GraphSnapshotService(
            RawDatabaseClient raw,
            RawJobGovernanceService governance,
            GraphMasterStore masterStore,
            @Value("${app.graph.master.max-edges:30000}") int maxMasterEdges,
            @Value("${app.graph.master.build-query-timeout-seconds:900}") int finalQueryTimeoutSeconds,
            @Value("${app.graph.master.batch-query-timeout-seconds:240}") int batchQueryTimeoutSeconds,
            @Value("${app.graph.master.batch-id-span:10000}") long batchIdSpan,
            @Value("${app.graph.master.minimum-split-span:500}") long minimumSplitSpan
    ) {
        this.raw = raw;
        this.governance = governance;
        this.masterStore = masterStore;
        this.maxMasterEdges = Math.max(2000, Math.min(maxMasterEdges, 100000));
        this.finalQueryTimeoutSeconds = Math.max(120, Math.min(finalQueryTimeoutSeconds, 3600));
        this.batchQueryTimeoutSeconds = Math.max(60, Math.min(batchQueryTimeoutSeconds, 1200));
        this.batchIdSpan = Math.max(1000L, Math.min(batchIdSpan, 50000L));
        this.minimumSplitSpan = Math.max(100L, Math.min(minimumSplitSpan, this.batchIdSpan));
    }

    /**
     * 启动后台构建。请求立即返回。
     */
    public Map<String, Object> rebuildAsync() {
        if (!building.compareAndSet(false, true)) {
            return status();
        }

        String buildId = UUID.randomUUID().toString();
        setState(buildId, "BUILDING", "准备分批构建全量母图", 0, "");

        executor.submit(() -> {
            try {
                rebuildNow(buildId);
            } catch (Exception e) {
                log.error("全量母图构建失败", e);
                setState(buildId, "FAILED", "全量母图构建失败", 100, rootMessage(e));
            } finally {
                building.set(false);
            }
        });

        return status();
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>(buildState.get());
        result.put("building", building.get());
        result.put("store", masterStore.storeInfo());

        try {
            Map<String, Object> progress = governance.progressSnapshot();
            long currentDataSnapshot = number(progress.get("processedCount")).longValue();
            long target = number(progress.get("targetCount")).longValue();
            long masterSnapshot = masterStore.snapshotVersion();

            result.put("currentDataSnapshot", currentDataSnapshot);
            result.put("targetCount", target);
            result.put("masterSnapshotVersion", masterSnapshot);
            result.put("pendingRows", Math.max(0L, currentDataSnapshot - masterSnapshot));
            result.put("fullGovernanceComplete", target > 0L && currentDataSnapshot >= target);
        } catch (Exception e) {
            result.put("currentDataSnapshot", 0L);
            result.put("targetCount", 0L);
            result.put("masterSnapshotVersion", masterStore.snapshotVersion());
            result.put("pendingRows", 0L);
            result.put("progressError", rootMessage(e));
        }

        return result;
    }

    private void rebuildNow(String buildId) {
        if (!raw.ping()) {
            throw new IllegalStateException("百万岗位 MySQL 数据源当前不可用");
        }

        governance.ensureSchema();
        ensureAggregationTables();
        verifyBaseIndexes();

        Map<String, Object> progress = governance.progressSnapshot();
        long snapshotVersion = number(progress.get("processedCount")).longValue();
        long validCount = number(progress.get("validCount")).longValue();
        long targetCount = number(progress.get("targetCount")).longValue();
        boolean full = targetCount > 0L && snapshotVersion >= targetCount;

        if (snapshotVersion <= 0L || validCount <= 0L) {
            throw new IllegalStateException("当前尚无可用于构建图谱的治理数据");
        }

        String jobs = quoted(RawJobGovernanceService.GOVERNED_TABLE);
        String skills = quoted(RawJobGovernanceService.SKILL_TABLE);

        setState(buildId, "BUILDING", "初始化母图分批聚合工作区", 5, "");
        clearAggregationTables();

        Map<String, Object> rangeRow = raw.one(
                "SELECT MIN(raw_job_id) AS min_id, MAX(raw_job_id) AS max_id FROM " + jobs
        );

        long minRawId = number(rangeRow.get("min_id")).longValue();
        long maxRawId = number(rangeRow.get("max_id")).longValue();

        if (maxRawId <= 0L || maxRawId < minRawId) {
            throw new IllegalStateException("治理岗位表中没有可读取的 raw_job_id 范围");
        }

        long startExclusive = Math.max(-1L, minRawId - 1L);
        long totalSpan = Math.max(1L, maxRawId - startExclusive);
        long totalBatches = Math.max(1L, (totalSpan + batchIdSpan - 1L) / batchIdSpan);
        long batchNo = 0L;

        setState(
                buildId,
                "BUILDING",
                "开始分批聚合岗位与技能证据",
                8,
                ""
        );

        while (startExclusive < maxRawId) {
            long endInclusive = Math.min(maxRawId, startExclusive + batchIdSpan);
            batchNo++;

            aggregateRoleRangeAdaptive(jobs, startExclusive, endInclusive, 0);
            aggregateEdgeRangeAdaptive(jobs, skills, startExclusive, endInclusive, 0);

            int progressPercent = 8 + (int) Math.min(
                    64L,
                    Math.round(64D * batchNo / Math.max(1D, totalBatches))
            );

            setState(
                    buildId,
                    "BUILDING",
                    "分批聚合母图证据 " + batchNo + "/" + totalBatches +
                            " · raw_id ≤ " + endInclusive,
                    progressPercent,
                    ""
            );

            startExclusive = endInclusive;
        }

        setState(buildId, "BUILDING", "从聚合工作表读取岗位节点", 74, "");

        List<Map<String, Object>> roleRows = longQuery(
                "SELECT " +
                        "role_name," +
                        "tech_stack," +
                        "level_name," +
                        "sample_count," +
                        "weighted_sample_count," +
                        "CASE WHEN quality_count<=0 THEN 0 ELSE quality_sum/quality_count END AS confidence " +
                        "FROM " + quoted(ROLE_WORK_TABLE) + " " +
                        "ORDER BY weighted_sample_count DESC"
        );

        setState(buildId, "BUILDING", "从聚合工作表读取岗位—技能关系", 78, "");

        List<Map<String, Object>> edgeRows = longQuery(
                "SELECT " +
                        "role_name," +
                        "tech_stack," +
                        "level_name," +
                        "skill_name," +
                        "skill_stack," +
                        "category," +
                        "evidence_count," +
                        "support_weight," +
                        "CASE WHEN confidence_count<=0 THEN 0 ELSE confidence_sum/confidence_count END AS confidence," +
                        "required_weight," +
                        "bonus_weight " +
                        "FROM " + quoted(EDGE_WORK_TABLE) + " " +
                        "ORDER BY support_weight DESC " +
                        "LIMIT " + maxMasterEdges
        );

        if (edgeRows.isEmpty()) {
            throw new IllegalStateException(
                    "母图聚合完成，但没有得到岗位—技能关系。请确认已治理 JD 中存在技能抽取结果。"
            );
        }

        setState(buildId, "BUILDING", "组装母图节点与关系", 82, "");

        Map<String, Map<String, Object>> roleMeta = new LinkedHashMap<>();
        for (Map<String, Object> row : roleRows) {
            String roleName = text(row.get("role_name"));
            if (roleName.isBlank()) {
                continue;
            }
            roleMeta.put(
                    roleKey(roleName, text(row.get("tech_stack")), text(row.get("level_name"))),
                    row
            );
        }

        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        Map<String, String> roleIds = new LinkedHashMap<>();
        Map<String, String> skillIds = new LinkedHashMap<>();
        Map<String, SkillAggregate> skillAggregates = new LinkedHashMap<>();
        Set<String> usedRoleKeys = new LinkedHashSet<>();

        for (Map<String, Object> edge : edgeRows) {
            String roleName = text(edge.get("role_name"));
            String techStack = safeValue(text(edge.get("tech_stack")), "未分类");
            String levelName = safeValue(text(edge.get("level_name")), "未标注");
            String skillName = text(edge.get("skill_name"));

            if (roleName.isBlank() || skillName.isBlank()) {
                continue;
            }

            String roleKey = roleKey(roleName, techStack, levelName);
            usedRoleKeys.add(roleKey);

            SkillAggregate aggregate = skillAggregates.computeIfAbsent(
                    skillName,
                    key -> new SkillAggregate(
                            skillName,
                            safeValue(text(edge.get("skill_stack")), "未分类"),
                            safeValue(text(edge.get("category")), "技能点")
                    )
            );

            aggregate.supportWeight += number(edge.get("support_weight")).doubleValue();
            aggregate.requiredWeight += number(edge.get("required_weight")).doubleValue();
            aggregate.bonusWeight += number(edge.get("bonus_weight")).doubleValue();
            aggregate.evidenceCount += number(edge.get("evidence_count")).longValue();
            aggregate.confidenceSum += number(edge.get("confidence")).doubleValue();
            aggregate.confidenceTerms++;
            aggregate.roleKeys.add(roleKey);
        }

        for (String roleKey : usedRoleKeys) {
            Map<String, Object> role = roleMeta.get(roleKey);
            if (role == null) {
                continue;
            }

            String roleName = text(role.get("role_name"));
            String techStack = safeValue(text(role.get("tech_stack")), "未分类");
            String levelName = safeValue(text(role.get("level_name")), "未标注");
            String id = stableId("role", roleKey);

            Map<String, Object> node = baseNode(id, roleName, "ROLE", techStack, levelName);
            long sampleCount = number(role.get("sample_count")).longValue();
            double weightedSamples = number(role.get("weighted_sample_count")).doubleValue();
            double confidence = number(role.get("confidence")).doubleValue();

            node.put("sampleCount", sampleCount);
            node.put("weightedSampleCount", round6(weightedSamples));
            node.put("confidence", round6(confidence));
            node.put("importance", round6(clamp01(Math.log1p(weightedSamples) / Math.log(800D))));

            nodes.put(id, node);
            roleIds.put(roleKey, id);
        }

        for (SkillAggregate aggregate : skillAggregates.values()) {
            String id = stableId("skill", aggregate.name);
            double averageConfidence = aggregate.confidenceTerms == 0
                    ? 0D
                    : aggregate.confidenceSum / aggregate.confidenceTerms;

            Map<String, Object> node = baseNode(
                    id,
                    aggregate.name,
                    "SKILL",
                    aggregate.stack,
                    aggregate.category
            );
            node.put("supportWeight", round6(aggregate.supportWeight));
            node.put("requiredWeight", round6(aggregate.requiredWeight));
            node.put("bonusWeight", round6(aggregate.bonusWeight));
            node.put("evidenceCount", aggregate.evidenceCount);
            node.put("roleCount", aggregate.roleKeys.size());
            node.put("confidence", round6(averageConfidence));
            node.put("importance", round6(clamp01(Math.log1p(aggregate.supportWeight) / Math.log(180D))));

            nodes.put(id, node);
            skillIds.put(aggregate.name, id);
        }

        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> connectedNodeIds = new LinkedHashSet<>();

        for (Map<String, Object> edge : edgeRows) {
            String roleName = text(edge.get("role_name"));
            String techStack = safeValue(text(edge.get("tech_stack")), "未分类");
            String levelName = safeValue(text(edge.get("level_name")), "未标注");
            String skillName = text(edge.get("skill_name"));

            String roleId = roleIds.get(roleKey(roleName, techStack, levelName));
            String skillId = skillIds.get(skillName);
            if (roleId == null || skillId == null) {
                continue;
            }

            double supportWeight = number(edge.get("support_weight")).doubleValue();
            double confidence = number(edge.get("confidence")).doubleValue();
            long evidenceCount = number(edge.get("evidence_count")).longValue();
            double requiredWeight = number(edge.get("required_weight")).doubleValue();
            double bonusWeight = number(edge.get("bonus_weight")).doubleValue();
            double typedWeight = requiredWeight + bonusWeight;

            double requiredRatio = typedWeight <= 0D ? 0D : requiredWeight / typedWeight;
            double bonusRatio = typedWeight <= 0D ? 0D : bonusWeight / typedWeight;
            String relationType = relationType(requiredWeight, bonusWeight);

            Map<String, Object> link = new LinkedHashMap<>();
            link.put("source", roleId);
            link.put("target", skillId);
            link.put("type", relationType);
            link.put("relationLabel", relationLabel(relationType));
            link.put("weight", round6(clamp01(Math.log1p(supportWeight) / Math.log(140D))));
            link.put("supportWeight", round6(supportWeight));
            link.put("requiredWeight", round6(requiredWeight));
            link.put("bonusWeight", round6(bonusWeight));
            link.put("requiredRatio", round6(requiredRatio));
            link.put("bonusRatio", round6(bonusRatio));
            link.put("confidence", round6(confidence));
            link.put("evidenceCount", evidenceCount);
            link.put("techStack", techStack);
            link.put("level", levelName);

            links.add(link);
            connectedNodeIds.add(roleId);
            connectedNodeIds.add(skillId);
        }

        nodes.entrySet().removeIf(entry -> !connectedNodeIds.contains(entry.getKey()));
        links.sort(Comparator.comparingDouble(
                (Map<String, Object> link) -> number(link.get("supportWeight")).doubleValue()
        ).reversed());

        setState(buildId, "BUILDING", "生成母图元数据并持久化", 92, "");

        List<Map<String, Object>> stackDistribution = buildStackDistribution(nodes.values());
        List<String> techStacks = distinctNodeValues(nodes.values(), "stack", "ROLE");
        List<String> levels = distinctNodeValues(nodes.values(), "meta", "ROLE");
        levels.sort(Comparator.comparingInt(this::levelOrder).thenComparing(String::compareTo));

        long roleCount = nodes.values().stream().filter(node -> "ROLE".equals(text(node.get("type")))).count();
        long skillCount = nodes.values().stream().filter(node -> "SKILL".equals(text(node.get("type")))).count();
        long requiredCount = links.stream().filter(link -> "REQUIRED".equals(text(link.get("type")))).count();
        long bonusCount = links.stream().filter(link -> "BONUS".equals(text(link.get("type")))).count();
        long mentionedCount = links.stream().filter(link -> "MENTIONED".equals(text(link.get("type")))).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("roleCount", roleCount);
        summary.put("skillCount", skillCount);
        summary.put("nodeCount", nodes.size());
        summary.put("linkCount", links.size());
        summary.put("requiredCount", requiredCount);
        summary.put("bonusCount", bonusCount);
        summary.put("mentionedCount", mentionedCount);
        summary.put("validJobCount", validCount);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("snapshotVersion", snapshotVersion);
        meta.put("targetCount", targetCount);
        meta.put("validJobCount", validCount);
        meta.put("generatedAt", LocalDateTime.now().toString());
        meta.put("scope", full ? "FULL_GOVERNED_SNAPSHOT" : "PARTIAL_GOVERNED_SNAPSHOT");
        meta.put("fullGovernanceComplete", full);
        meta.put("maxMasterEdges", maxMasterEdges);
        meta.put("actualMasterEdges", links.size());
        meta.put("buildMethod", "BATCHED_RAW_ID_AGGREGATION_V14");
        meta.put("batchIdSpan", batchIdSpan);
        meta.put("description", "基于当前全部有效治理 JD 分批聚合生成岗位—技能母图；页面筛选不再访问百万数据表");

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("techStacks", techStacks);
        options.put("levels", levels);
        options.put("evidenceOptions", List.of(1, 2, 3, 5, 10, 20, 50));
        options.put("sizes", List.of(
                sizeOption("small", "小图谱", 240, "适合快速查看和答辩演示"),
                sizeOption("medium", "中图谱", 650, "推荐日常分析"),
                sizeOption("large", "大图谱", 1200, "适合全景能力探索")
        ));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("available", true);
        snapshot.put("nodes", new ArrayList<>(nodes.values()));
        snapshot.put("links", links);
        snapshot.put("summary", summary);
        snapshot.put("stacks", stackDistribution);
        snapshot.put("palette", GRAPH_PALETTE);
        snapshot.put("options", options);
        snapshot.put("meta", meta);
        snapshot.put("source", "PERSISTED_MASTER_GRAPH");

        masterStore.replaceAndPersist(snapshot);
        setState(buildId, "COMPLETED", "全量母图已更新并切换为新快照", 100, "");
    }

    /**
     * 分批岗位聚合。若某个范围持续发生瞬时超时，则自动把范围二分后继续。
     */
    /**
     * 分批岗位聚合。
     *
     * V14 修复：兼容 MySQL ONLY_FULL_GROUP_BY。
     * 先在派生表中完成 GROUP BY，再由外层 SELECT 计算 role_key，
     * 避免 SHA2(CONCAT_WS(...g.tech_stack...)) 这类表达式被 MySQL
     * 误判为“SELECT 中引用了未参与 GROUP BY 的原始列”。
     */
    private void aggregateRoleRangeAdaptive(
            String jobs,
            long startExclusive,
            long endInclusive,
            int depth
    ) {
        String sql =
                "INSERT INTO " + quoted(ROLE_WORK_TABLE) + "(" +
                        "role_key,role_name,tech_stack,level_name," +
                        "sample_count,weighted_sample_count,quality_sum,quality_count" +
                        ") " +
                        "SELECT " +
                        "SHA2(CONCAT_WS(CHAR(31)," +
                        "agg.role_name,agg.tech_stack,agg.level_name" +
                        "),256) AS role_key," +
                        "agg.role_name," +
                        "agg.tech_stack," +
                        "agg.level_name," +
                        "agg.sample_count," +
                        "agg.weighted_sample_count," +
                        "agg.quality_sum," +
                        "agg.quality_count " +
                        "FROM (" +
                        "SELECT " +
                        "g.title_standard AS role_name," +
                        "COALESCE(NULLIF(TRIM(g.tech_stack),''),'未分类') AS tech_stack," +
                        "COALESCE(NULLIF(TRIM(g.level_name),''),'未标注') AS level_name," +
                        "COUNT(*) AS sample_count," +
                        "SUM(COALESCE(g.duplicate_weight,1)) AS weighted_sample_count," +
                        "SUM(COALESCE(g.quality_score,0)) AS quality_sum," +
                        "COUNT(*) AS quality_count " +
                        "FROM " + jobs + " g " +
                        "WHERE g.raw_job_id>? AND g.raw_job_id<=? " +
                        "AND g.valid_for_analysis=1 " +
                        "AND COALESCE(g.is_deleted,0)=0 " +
                        "AND g.title_standard IS NOT NULL " +
                        "AND TRIM(g.title_standard)<>'' " +
                        "GROUP BY " +
                        "g.title_standard," +
                        "COALESCE(NULLIF(TRIM(g.tech_stack),''),'未分类')," +
                        "COALESCE(NULLIF(TRIM(g.level_name),''),'未标注')" +
                        ") agg " +
                        "ON DUPLICATE KEY UPDATE " +
                        "sample_count=sample_count+VALUES(sample_count)," +
                        "weighted_sample_count=weighted_sample_count+VALUES(weighted_sample_count)," +
                        "quality_sum=quality_sum+VALUES(quality_sum)," +
                        "quality_count=quality_count+VALUES(quality_count)";

        executeAdaptiveRangeUpdate(
                sql,
                startExclusive,
                endInclusive,
                depth,
                "岗位聚合"
        );
    }

    /**
     * 分批岗位—技能关系聚合。
     *
     * V14 修复：兼容 ONLY_FULL_GROUP_BY。
     * GROUP BY 全部放在内层派生表中；edge_key / role_key 只在外层
     * 基于已经聚合完成的别名列计算，因此不再直接引用 g.tech_stack、
     * g.level_name 等原始列参与哈希表达式。
     */
    private void aggregateEdgeRangeAdaptive(
            String jobs,
            String skills,
            long startExclusive,
            long endInclusive,
            int depth
    ) {
        String sql =
                "INSERT INTO " + quoted(EDGE_WORK_TABLE) + "(" +
                        "edge_key,role_key,role_name,tech_stack,level_name," +
                        "skill_name,skill_stack,category," +
                        "evidence_count,support_weight,confidence_sum,confidence_count," +
                        "required_weight,bonus_weight" +
                        ") " +
                        "SELECT " +
                        "SHA2(CONCAT_WS(CHAR(31)," +
                        "agg.role_name,agg.tech_stack,agg.level_name,agg.skill_name" +
                        "),256) AS edge_key," +
                        "SHA2(CONCAT_WS(CHAR(31)," +
                        "agg.role_name,agg.tech_stack,agg.level_name" +
                        "),256) AS role_key," +
                        "agg.role_name," +
                        "agg.tech_stack," +
                        "agg.level_name," +
                        "agg.skill_name," +
                        "agg.skill_stack," +
                        "agg.category," +
                        "agg.evidence_count," +
                        "agg.support_weight," +
                        "agg.confidence_sum," +
                        "agg.confidence_count," +
                        "agg.required_weight," +
                        "agg.bonus_weight " +
                        "FROM (" +
                        "SELECT " +
                        "g.title_standard AS role_name," +
                        "COALESCE(NULLIF(TRIM(g.tech_stack),''),'未分类') AS tech_stack," +
                        "COALESCE(NULLIF(TRIM(g.level_name),''),'未标注') AS level_name," +
                        "s.skill_name AS skill_name," +
                        "MAX(COALESCE(NULLIF(TRIM(s.tech_stack),''),'未分类')) AS skill_stack," +
                        "MAX(COALESCE(NULLIF(TRIM(s.category),''),'技能点')) AS category," +
                        "COUNT(*) AS evidence_count," +
                        "SUM(COALESCE(g.duplicate_weight,1)*COALESCE(s.confidence,0)) AS support_weight," +
                        "SUM(COALESCE(s.confidence,0)) AS confidence_sum," +
                        "COUNT(*) AS confidence_count," +
                        "SUM(CASE WHEN UPPER(COALESCE(s.requirement_type,''))='REQUIRED' " +
                        "THEN COALESCE(g.duplicate_weight,1)*COALESCE(s.confidence,0) ELSE 0 END) AS required_weight," +
                        "SUM(CASE WHEN UPPER(COALESCE(s.requirement_type,'')) IN ('BONUS','PREFERRED') " +
                        "THEN COALESCE(g.duplicate_weight,1)*COALESCE(s.confidence,0) ELSE 0 END) AS bonus_weight " +
                        "FROM " + jobs + " g STRAIGHT_JOIN " + skills + " s ON s.raw_job_id=g.raw_job_id " +
                        "WHERE g.raw_job_id>? AND g.raw_job_id<=? " +
                        "AND g.valid_for_analysis=1 " +
                        "AND COALESCE(g.is_deleted,0)=0 " +
                        "AND g.title_standard IS NOT NULL " +
                        "AND TRIM(g.title_standard)<>'' " +
                        "AND s.skill_name IS NOT NULL " +
                        "AND TRIM(s.skill_name)<>'' " +
                        "GROUP BY " +
                        "g.title_standard," +
                        "COALESCE(NULLIF(TRIM(g.tech_stack),''),'未分类')," +
                        "COALESCE(NULLIF(TRIM(g.level_name),''),'未标注')," +
                        "s.skill_name" +
                        ") agg " +
                        "ON DUPLICATE KEY UPDATE " +
                        "skill_stack=VALUES(skill_stack)," +
                        "category=VALUES(category)," +
                        "evidence_count=evidence_count+VALUES(evidence_count)," +
                        "support_weight=support_weight+VALUES(support_weight)," +
                        "confidence_sum=confidence_sum+VALUES(confidence_sum)," +
                        "confidence_count=confidence_count+VALUES(confidence_count)," +
                        "required_weight=required_weight+VALUES(required_weight)," +
                        "bonus_weight=bonus_weight+VALUES(bonus_weight)";

        executeAdaptiveRangeUpdate(
                sql,
                startExclusive,
                endInclusive,
                depth,
                "岗位—技能关系聚合"
        );
    }

    private void executeAdaptiveRangeUpdate(
            String sql,
            long startExclusive,
            long endInclusive,
            int depth,
            String label
    ) {
        try {
            executeRangeUpdateWithRetry(sql, startExclusive, endInclusive, label);
        } catch (RuntimeException e) {
            long span = endInclusive - startExclusive;

            if (!isTransientBuildError(e)
                    || span <= minimumSplitSpan
                    || depth >= 12) {
                throw e;
            }

            long mid = startExclusive + span / 2L;
            if (mid <= startExclusive || mid >= endInclusive) {
                throw e;
            }

            log.warn(
                    "{}范围 {}~{} 连续失败，自动二分为 {}~{} 与 {}~{}：{}",
                    label,
                    startExclusive + 1,
                    endInclusive,
                    startExclusive + 1,
                    mid,
                    mid + 1,
                    endInclusive,
                    rootMessage(e)
            );

            executeAdaptiveRangeUpdate(sql, startExclusive, mid, depth + 1, label);
            executeAdaptiveRangeUpdate(sql, mid, endInclusive, depth + 1, label);
        }
    }

    private void executeRangeUpdateWithRetry(
            String sql,
            long startExclusive,
            long endInclusive,
            String label
    ) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                raw.jdbc().update(connection -> {
                    var statement = connection.prepareStatement(sql);
                    statement.setQueryTimeout(batchQueryTimeoutSeconds);
                    statement.setLong(1, startExclusive);
                    statement.setLong(2, endInclusive);
                    return statement;
                });
                return;
            } catch (RuntimeException e) {
                last = e;

                if (!isTransientBuildError(e) || attempt >= 3) {
                    break;
                }

                long sleepMs = 800L * attempt;
                log.warn(
                        "{}范围 {}~{} 第 {}/3 次执行失败，{}ms 后重试：{}",
                        label,
                        startExclusive + 1,
                        endInclusive,
                        attempt,
                        sleepMs,
                        rootMessage(e)
                );

                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("母图构建线程被中断", interruptedException);
                }
            }
        }

        throw last == null
                ? new IllegalStateException(label + "执行失败")
                : last;
    }

    /**
     * 最终只查询已经压缩后的工作表，因此即便 timeout 较短也足够。
     */
    private List<Map<String, Object>> longQuery(String sql) {
        return raw.jdbc().query(
                connection -> {
                    var statement = connection.prepareStatement(sql);
                    statement.setQueryTimeout(finalQueryTimeoutSeconds);
                    statement.setFetchSize(1000);
                    return statement;
                },
                new ColumnMapRowMapper()
        );
    }

    private void ensureAggregationTables() {
        raw.update(
                "CREATE TABLE IF NOT EXISTS " + quoted(ROLE_WORK_TABLE) + " (" +
                        "role_key CHAR(64) PRIMARY KEY," +
                        "role_name VARCHAR(500) NOT NULL," +
                        "tech_stack VARCHAR(120) NOT NULL," +
                        "level_name VARCHAR(80) NOT NULL," +
                        "sample_count BIGINT NOT NULL DEFAULT 0," +
                        "weighted_sample_count DOUBLE NOT NULL DEFAULT 0," +
                        "quality_sum DOUBLE NOT NULL DEFAULT 0," +
                        "quality_count BIGINT NOT NULL DEFAULT 0," +
                        "INDEX idx_graph_role_weight(weighted_sample_count)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        );

        raw.update(
                "CREATE TABLE IF NOT EXISTS " + quoted(EDGE_WORK_TABLE) + " (" +
                        "edge_key CHAR(64) PRIMARY KEY," +
                        "role_key CHAR(64) NOT NULL," +
                        "role_name VARCHAR(500) NOT NULL," +
                        "tech_stack VARCHAR(120) NOT NULL," +
                        "level_name VARCHAR(80) NOT NULL," +
                        "skill_name VARCHAR(200) NOT NULL," +
                        "skill_stack VARCHAR(120) NOT NULL," +
                        "category VARCHAR(120) NOT NULL," +
                        "evidence_count BIGINT NOT NULL DEFAULT 0," +
                        "support_weight DOUBLE NOT NULL DEFAULT 0," +
                        "confidence_sum DOUBLE NOT NULL DEFAULT 0," +
                        "confidence_count BIGINT NOT NULL DEFAULT 0," +
                        "required_weight DOUBLE NOT NULL DEFAULT 0," +
                        "bonus_weight DOUBLE NOT NULL DEFAULT 0," +
                        "INDEX idx_graph_edge_role(role_key)," +
                        "INDEX idx_graph_edge_support(support_weight)," +
                        "INDEX idx_graph_edge_stack_level(tech_stack,level_name)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        );
    }

    private void clearAggregationTables() {
        raw.update("TRUNCATE TABLE " + quoted(ROLE_WORK_TABLE));
        raw.update("TRUNCATE TABLE " + quoted(EDGE_WORK_TABLE));
    }

    /**
     * V4/V7 的治理表结构已经包含：
     * - zhitu_governed_job.raw_job_id PRIMARY KEY
     * - zhitu_governed_job_skill(raw_job_id, skill_name) UNIQUE
     * - zhitu_governed_job_skill.raw_job_id INDEX
     *
     * 因此 V14 不再在“点击构图”时对百万表临时 CREATE INDEX，
     * 避免索引 DDL 自身阻塞或超时。
     */
    private void verifyBaseIndexes() {
        if (!raw.indexOnColumnExists(RawJobGovernanceService.SKILL_TABLE, "raw_job_id")) {
            log.warn(
                    "{} 缺少 raw_job_id 索引，母图聚合会明显变慢。建议业务空闲时补充该索引。",
                    RawJobGovernanceService.SKILL_TABLE
            );
        }
    }

    private boolean isTransientBuildError(Throwable throwable) {
        String message = rootMessage(throwable).toLowerCase(Locale.ROOT);

        return message.contains("timeout")
                || message.contains("cancel")
                || message.contains("communications link failure")
                || message.contains("connection")
                || message.contains("lock wait")
                || message.contains("deadlock")
                || message.contains("socket")
                || message.contains("broken pipe");
    }

    private List<Map<String, Object>> buildStackDistribution(Iterable<Map<String, Object>> nodes) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            if (!"ROLE".equals(text(node.get("type")))) {
                continue;
            }
            String stack = safeValue(text(node.get("stack")), "未分类");
            long sampleCount = number(node.get("sampleCount")).longValue();
            counts.merge(stack, sampleCount, Long::sum);
        }

        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", entry.getKey());
                    item.put("value", entry.getValue());
                    return item;
                })
                .toList();
    }

    private List<String> distinctNodeValues(Iterable<Map<String, Object>> nodes, String field, String type) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            if (!type.equals(text(node.get("type")))) {
                continue;
            }
            String value = text(node.get(field));
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return new ArrayList<>(values);
    }

    private static Map<String, Object> baseNode(
            String id,
            String name,
            String type,
            String stack,
            String meta
    ) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("name", name);
        node.put("type", type);
        node.put("stack", stack);
        node.put("meta", meta);
        return node;
    }

    private static String stableId(String prefix, String value) {
        UUID uuid = UUID.nameUUIDFromBytes((prefix + "\u0000" + value).getBytes(StandardCharsets.UTF_8));
        return prefix + "-" + uuid;
    }

    private static String roleKey(String roleName, String stack, String level) {
        return safeValue(roleName, "") + "\u0000" +
                safeValue(stack, "未分类") + "\u0000" +
                safeValue(level, "未标注");
    }

    private static String relationType(double requiredWeight, double bonusWeight) {
        if (requiredWeight <= 0D && bonusWeight <= 0D) {
            return "MENTIONED";
        }
        return requiredWeight >= bonusWeight ? "REQUIRED" : "BONUS";
    }

    private static String relationLabel(String type) {
        return switch (type) {
            case "REQUIRED" -> "必备技能";
            case "BONUS" -> "加分技能";
            default -> "相关技能";
        };
    }

    private static Map<String, Object> sizeOption(
            String value,
            String label,
            int limit,
            String description
    ) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("value", value);
        item.put("label", label);
        item.put("limit", limit);
        item.put("description", description);
        return item;
    }

    private int levelOrder(String value) {
        String level = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (level.contains("实习")) return 0;
        if (level.contains("初级") || level.contains("junior")) return 1;
        if (level.contains("中级") || level.contains("middle")) return 2;
        if (level.contains("高级") || level.contains("senior")) return 3;
        if (level.contains("专家") || level.contains("lead") || level.contains("principal")) return 4;
        if (level.contains("未标注")) return 99;
        return 20;
    }

    private static String quoted(String table) {
        return "`" + table + "`";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String safeValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Number number(Object value) {
        if (value instanceof Number number) {
            return number;
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

    private static double clamp01(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000D) / 1_000_000D;
    }

    private void setState(
            String buildId,
            String status,
            String stage,
            int progress,
            String error
    ) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("buildId", buildId);
        state.put("status", status);
        state.put("stage", stage);
        state.put("progress", progress);
        state.put("error", error == null ? "" : error);
        state.put("updatedAt", LocalDateTime.now().toString());
        buildState.set(state);
    }

    private static Map<String, Object> idleState(String status, String stage) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("buildId", "");
        state.put("status", status);
        state.put("stage", stage);
        state.put("progress", 0);
        state.put("error", "");
        state.put("updatedAt", LocalDateTime.now().toString());
        return state;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank()
                ? cursor.getClass().getSimpleName()
                : message;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static final class SkillAggregate {
        private final String name;
        private final String stack;
        private final String category;
        private double supportWeight;
        private double requiredWeight;
        private double bonusWeight;
        private long evidenceCount;
        private double confidenceSum;
        private int confidenceTerms;
        private final Set<String> roleKeys = new LinkedHashSet<>();

        private SkillAggregate(String name, String stack, String category) {
            this.name = name;
            this.stack = stack;
            this.category = category;
        }
    }
}