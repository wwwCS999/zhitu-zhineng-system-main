package com.zhitu.service;

import com.zhitu.repository.RawDatabaseClient;
import com.zhitu.repository.Store;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 岗位能力图谱服务（百万 JD 超时优化版）。
 *
 * 主要优化：
 * 1. 图谱结果按“技术栈 + 级别 + 规模 + 证据阈值”缓存；页面第一次进入优先显示缓存，
 *    用户点击“构建 / 更新图谱”才重新计算当前快照；
 * 2. 去掉百万关系聚合中的 COUNT(DISTINCT CONCAT(...))，改为 COUNT(*) 作为原始证据数，
 *    可信强度仍使用 duplicate_weight * confidence，从而保留重复模板降权；
 * 3. WHERE / GROUP BY 不再对 tech_stack、level_name 大量使用 TRIM/COALESCE 包裹，
 *    避免索引完全失效；
 * 4. 先选 Top 岗位，再利用 title_standard 索引缩小岗位集合，随后 STRAIGHT_JOIN skill.raw_job_id；
 * 5. panorama 不再额外执行一次技术栈全表分布查询；筛选项由 options() 单独缓存；
 * 6. 所有旧方法签名保留，避免影响总控智能体和其他模块。
 */
@Service
public class GraphService {

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

    private static final long OPTIONS_CACHE_MILLIS = 5 * 60_000L;
    private static final int MAX_GRAPH_CACHE_ENTRIES = 12;

    private final Store store;
    private final RawDatabaseClient raw;
    private final RawJobGovernanceService governance;

    private final Object optionsLock = new Object();
    private volatile OptionsCache optionsCache;

    /**
     * accessOrder=true：保留最近使用的筛选组合，避免内存无限增长。
     */
    private final Map<GraphCacheKey, GraphCacheEntry> graphCache =
            new LinkedHashMap<>(16, 0.75f, true);

    private final Object graphCacheLock = new Object();

    public GraphService(
            Store store,
            RawDatabaseClient raw,
            RawJobGovernanceService governance
    ) {
        this.store = store;
        this.raw = raw;
        this.governance = governance;
    }

    public Map<String, Object> options() {
        return options(false);
    }

    public Map<String, Object> options(boolean refresh) {
        long now = System.currentTimeMillis();
        OptionsCache current = optionsCache;

        if (!refresh
                && current != null
                && now - current.createdAtMillis() < OPTIONS_CACHE_MILLIS) {
            return current.payload();
        }

        synchronized (optionsLock) {
            current = optionsCache;
            now = System.currentTimeMillis();

            if (!refresh
                    && current != null
                    && now - current.createdAtMillis() < OPTIONS_CACHE_MILLIS) {
                return current.payload();
            }

            if (!raw.ping()) {
                if (current != null) {
                    return current.payload();
                }
                throw new IllegalStateException("百万岗位 MySQL 数据源当前不可用");
            }

            governance.ensureSchema();
            String jobs = quoted(RawJobGovernanceService.GOVERNED_TABLE);

            /*
             * 不使用 TRIM/COALESCE 包裹 GROUP BY 字段，减少函数计算。
             * 空值统一在 Java 层映射为“未分类 / 未标注”。
             */
            List<Map<String, Object>> stackRows = raw.list(
                    "SELECT tech_stack AS value_name,COUNT(*) AS row_count " +
                            "FROM " + jobs + " " +
                            "WHERE valid_for_analysis=1 AND COALESCE(is_deleted,0)=0 " +
                            "GROUP BY tech_stack ORDER BY row_count DESC LIMIT 40"
            );

            List<String> techStacks = new ArrayList<>();
            for (Map<String, Object> row : stackRows) {
                String value = safeValue(text(row.get("value_name")), "未分类");
                if (!techStacks.contains(value)) {
                    techStacks.add(value);
                }
            }

            List<Map<String, Object>> levelRows = raw.list(
                    "SELECT level_name AS value_name,COUNT(*) AS row_count " +
                            "FROM " + jobs + " " +
                            "WHERE valid_for_analysis=1 AND COALESCE(is_deleted,0)=0 " +
                            "GROUP BY level_name ORDER BY row_count DESC"
            );

            List<String> levels = new ArrayList<>();
            for (Map<String, Object> row : levelRows) {
                String value = safeValue(text(row.get("value_name")), "未标注");
                if (!levels.contains(value)) {
                    levels.add(value);
                }
            }

            levels.sort(
                    Comparator.comparingInt(this::levelOrder)
                            .thenComparing(String::compareTo)
            );

            List<Map<String, Object>> sizes = List.of(
                    sizeOption("small", "小图谱", 240, "适合快速查看和答辩演示"),
                    sizeOption("medium", "中图谱", 650, "推荐日常分析"),
                    sizeOption("large", "大图谱", 1200, "适合全景能力探索")
            );

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("techStacks", techStacks);
            result.put("levels", levels);
            result.put("sizes", sizes);
            result.put("evidenceOptions", List.of(1, 2, 3, 5, 10, 20, 50));
            result.put("palette", GRAPH_PALETTE);
            result.put("source", "MYSQL_GOVERNED_MILLION_JD");
            result.put("generatedAt", Instant.now().toString());

            optionsCache = new OptionsCache(now, result);
            return result;
        }
    }

    /**
     * 保持原调用兼容。
     */
    public Map<String, Object> panorama(
            String stack,
            String level,
            int limit
    ) {
        return panorama(stack, level, limit, 1, false);
    }

    /**
     * 保持原增强接口兼容。
     */
    public Map<String, Object> panorama(
            String stack,
            String level,
            int limit,
            int minEvidence
    ) {
        return panorama(stack, level, limit, minEvidence, false);
    }

    /**
     * @param refresh true：重新计算当前治理快照；false：相同筛选优先使用最近成功缓存。
     */
    public Map<String, Object> panorama(
            String stack,
            String level,
            int limit,
            int minEvidence,
            boolean refresh
    ) {
        int edgeLimit = Math.max(80, Math.min(limit, 1600));
        int evidenceThreshold = Math.max(1, Math.min(minEvidence, 10000));

        GraphCacheKey key = new GraphCacheKey(
                safe(stack),
                safe(level),
                edgeLimit,
                evidenceThreshold
        );

        if (!refresh) {
            synchronized (graphCacheLock) {
                GraphCacheEntry cached = graphCache.get(key);
                if (cached != null) {
                    return cached.payload();
                }
            }
        }

        if (!raw.ping()) {
            synchronized (graphCacheLock) {
                GraphCacheEntry cached = graphCache.get(key);
                if (cached != null) {
                    return withWarning(cached.payload(), "数据源暂时繁忙，已展示最近一次成功图谱");
                }
            }
            throw new IllegalStateException("百万岗位 MySQL 数据源当前不可用");
        }

        governance.ensureSchema();

        Map<String, Object> snapshot;
        try {
            snapshot = governance.progressSnapshot();
        } catch (Exception ignored) {
            snapshot = Map.of();
        }

        try {
            Map<String, Object> result = buildPanorama(
                    stack,
                    level,
                    edgeLimit,
                    evidenceThreshold,
                    snapshot
            );

            synchronized (graphCacheLock) {
                graphCache.put(
                        key,
                        new GraphCacheEntry(
                                number(snapshot.get("processedCount")).longValue(),
                                Instant.now().toString(),
                                result
                        )
                );
                trimGraphCache();
            }

            return result;
        } catch (RuntimeException e) {
            synchronized (graphCacheLock) {
                GraphCacheEntry cached = graphCache.get(key);
                if (cached != null) {
                    return withWarning(
                            cached.payload(),
                            "当前快照图谱聚合较慢，已展示最近一次成功结果：" + rootMessage(e)
                    );
                }
            }
            throw e;
        }
    }

    private Map<String, Object> buildPanorama(
            String stack,
            String level,
            int edgeLimit,
            int evidenceThreshold,
            Map<String, Object> snapshot
    ) {
        int roleLimit = edgeLimit <= 300 ? 24 : edgeLimit <= 800 ? 48 : 72;

        String jobs = quoted(RawJobGovernanceService.GOVERNED_TABLE);
        String skills = quoted(RawJobGovernanceService.SKILL_TABLE);

        /*
         * 第一步：只从岗位表选 Top 岗位。
         * sample_count 用 COUNT(*)，不再构造字符串做 COUNT DISTINCT。
         * duplicate_weight 仍会在第二步技能关系强度中参与降权。
         */
        StringBuilder roleSql = new StringBuilder(
                "SELECT " +
                        "title_standard AS role_name," +
                        "tech_stack AS tech_stack," +
                        "level_name AS level_name," +
                        "COUNT(*) AS sample_count," +
                        "AVG(quality_score) AS confidence " +
                        "FROM " + jobs + " " +
                        "WHERE valid_for_analysis=1 " +
                        "AND COALESCE(is_deleted,0)=0 " +
                        "AND title_standard IS NOT NULL AND title_standard<>'' "
        );

        List<Object> roleArgs = new ArrayList<>();

        if (nonBlank(stack)) {
            roleSql.append("AND tech_stack=? ");
            roleArgs.add(stack.trim());
        }

        if (nonBlank(level)) {
            roleSql.append("AND level_name=? ");
            roleArgs.add(level.trim());
        }

        roleSql.append(
                "GROUP BY title_standard,tech_stack,level_name " +
                        "ORDER BY sample_count DESC LIMIT "
        ).append(roleLimit);

        List<Map<String, Object>> roleRows = raw.list(
                roleSql.toString(),
                roleArgs.toArray()
        );

        if (roleRows.isEmpty()) {
            return emptyGraph(stack, level, edgeLimit, evidenceThreshold, snapshot);
        }

        Map<String, Map<String, Object>> roleMeta = new LinkedHashMap<>();
        LinkedHashSet<String> roleNamesSet = new LinkedHashSet<>();

        for (Map<String, Object> row : roleRows) {
            String roleName = text(row.get("role_name"));
            String techStack = safeValue(text(row.get("tech_stack")), "未分类");
            String levelName = safeValue(text(row.get("level_name")), "未标注");

            if (roleName.isBlank()) {
                continue;
            }

            roleNamesSet.add(roleName);
            roleMeta.put(roleKey(roleName, techStack, levelName), row);
        }

        List<String> roleNames = new ArrayList<>(roleNamesSet);
        if (roleNames.isEmpty()) {
            return emptyGraph(stack, level, edgeLimit, evidenceThreshold, snapshot);
        }

        String placeholders = String.join(",", roleNames.stream().map(value -> "?").toList());

        /*
         * 第二步：只对上一步 Top 岗位做技能聚合。
         *
         * - STRAIGHT_JOIN：先过滤岗位，再按 skill.raw_job_id 索引查技能；
         * - evidence_count 改成 COUNT(*)，避免 COUNT(DISTINCT CONCAT(...)) 的临时表/排序开销；
         * - duplicate_weight * confidence 仍然作为可信证据强度，不丢失治理逻辑。
         */
        StringBuilder edgeSql = new StringBuilder(
                "SELECT " +
                        "g.title_standard AS role_name," +
                        "g.tech_stack AS tech_stack," +
                        "g.level_name AS level_name," +
                        "s.skill_name AS skill_name," +
                        "MAX(s.tech_stack) AS skill_stack," +
                        "MAX(s.category) AS category," +
                        "SUM(COALESCE(g.duplicate_weight,1) * COALESCE(s.confidence,0)) AS support_weight," +
                        "AVG(COALESCE(s.confidence,0)) AS confidence," +
                        "COUNT(*) AS evidence_count," +
                        "SUM(CASE WHEN UPPER(COALESCE(s.requirement_type,''))='REQUIRED' " +
                        "THEN COALESCE(g.duplicate_weight,1)*COALESCE(s.confidence,0) ELSE 0 END) AS required_weight," +
                        "SUM(CASE WHEN UPPER(COALESCE(s.requirement_type,'')) IN ('BONUS','PREFERRED') " +
                        "THEN COALESCE(g.duplicate_weight,1)*COALESCE(s.confidence,0) ELSE 0 END) AS bonus_weight " +
                        "FROM " + jobs + " g " +
                        "STRAIGHT_JOIN " + skills + " s ON s.raw_job_id=g.raw_job_id " +
                        "WHERE g.valid_for_analysis=1 " +
                        "AND COALESCE(g.is_deleted,0)=0 " +
                        "AND g.title_standard IN (" + placeholders + ") "
        );

        List<Object> edgeArgs = new ArrayList<>(roleNames);

        if (nonBlank(stack)) {
            edgeSql.append("AND g.tech_stack=? ");
            edgeArgs.add(stack.trim());
        }

        if (nonBlank(level)) {
            edgeSql.append("AND g.level_name=? ");
            edgeArgs.add(level.trim());
        }

        edgeSql.append(
                "GROUP BY g.title_standard,g.tech_stack,g.level_name,s.skill_name " +
                        "HAVING COUNT(*) >= ? " +
                        "ORDER BY support_weight DESC LIMIT "
        ).append(edgeLimit);

        edgeArgs.add(evidenceThreshold);

        List<Map<String, Object>> edgeRows = raw.list(
                edgeSql.toString(),
                edgeArgs.toArray()
        );

        if (edgeRows.isEmpty()) {
            return emptyGraph(stack, level, edgeLimit, evidenceThreshold, snapshot);
        }

        return assembleGraph(
                roleMeta,
                edgeRows,
                stack,
                level,
                edgeLimit,
                evidenceThreshold,
                snapshot
        );
    }

    private Map<String, Object> assembleGraph(
            Map<String, Map<String, Object>> roleMeta,
            List<Map<String, Object>> edgeRows,
            String stack,
            String level,
            int edgeLimit,
            int evidenceThreshold,
            Map<String, Object> snapshot
    ) {
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        Map<String, String> roleIds = new LinkedHashMap<>();
        int nextRoleId = 1;

        for (Map.Entry<String, Map<String, Object>> entry : roleMeta.entrySet()) {
            Map<String, Object> role = entry.getValue();

            String roleName = text(role.get("role_name"));
            String techStack = safeValue(text(role.get("tech_stack")), "未分类");
            String levelName = safeValue(text(role.get("level_name")), "未标注");
            String id = "role-" + nextRoleId++;

            roleIds.put(entry.getKey(), id);

            Map<String, Object> node = baseNode(id, roleName, "ROLE", techStack, levelName);
            long sampleCount = number(role.get("sample_count")).longValue();
            double confidence = number(role.get("confidence")).doubleValue();

            node.put("sampleCount", sampleCount);
            node.put("confidence", round6(confidence));
            node.put("importance", round6(clamp01(Math.log1p(sampleCount) / Math.log(500D))));
            nodes.put(id, node);
        }

        Map<String, SkillAggregate> skillAggregates = new LinkedHashMap<>();

        for (Map<String, Object> edge : edgeRows) {
            String skillName = text(edge.get("skill_name"));
            if (skillName.isBlank()) {
                continue;
            }

            String skillStack = safeValue(text(edge.get("skill_stack")), "未分类");
            String category = safeValue(text(edge.get("category")), "技能点");

            SkillAggregate aggregate = skillAggregates.computeIfAbsent(
                    skillName,
                    key -> new SkillAggregate(skillName, skillStack, category)
            );

            aggregate.supportWeight += number(edge.get("support_weight")).doubleValue();
            aggregate.requiredWeight += number(edge.get("required_weight")).doubleValue();
            aggregate.bonusWeight += number(edge.get("bonus_weight")).doubleValue();
            aggregate.evidenceCount += number(edge.get("evidence_count")).longValue();
            aggregate.confidenceSum += number(edge.get("confidence")).doubleValue();
            aggregate.confidenceTerms++;
            aggregate.roleKeys.add(
                    roleKey(
                            text(edge.get("role_name")),
                            safeValue(text(edge.get("tech_stack")), "未分类"),
                            safeValue(text(edge.get("level_name")), "未标注")
                    )
            );
        }

        Map<String, String> skillIds = new LinkedHashMap<>();
        int nextSkillId = 1;

        for (SkillAggregate skill : skillAggregates.values()) {
            String id = "skill-" + nextSkillId++;
            skillIds.put(skill.name, id);

            Map<String, Object> node = baseNode(
                    id,
                    skill.name,
                    "SKILL",
                    safeValue(skill.stack, "未分类"),
                    safeValue(skill.category, "技能点")
            );

            double averageConfidence = skill.confidenceTerms == 0
                    ? 0D
                    : skill.confidenceSum / skill.confidenceTerms;

            node.put("supportWeight", round6(skill.supportWeight));
            node.put("requiredWeight", round6(skill.requiredWeight));
            node.put("bonusWeight", round6(skill.bonusWeight));
            node.put("evidenceCount", skill.evidenceCount);
            node.put("roleCount", skill.roleKeys.size());
            node.put("confidence", round6(averageConfidence));
            node.put("importance", round6(clamp01(Math.log1p(skill.supportWeight) / Math.log(120D))));
            nodes.put(id, node);
        }

        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> connectedRoleIds = new LinkedHashSet<>();
        Set<String> connectedSkillIds = new LinkedHashSet<>();

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

            String relationType;
            if (requiredWeight <= 0D && bonusWeight <= 0D) {
                relationType = "MENTIONED";
            } else if (requiredWeight >= bonusWeight) {
                relationType = "REQUIRED";
            } else {
                relationType = "BONUS";
            }

            Map<String, Object> link = new LinkedHashMap<>();
            link.put("source", roleId);
            link.put("target", skillId);
            link.put("type", relationType);
            link.put("relationLabel", relationLabel(relationType));
            link.put("weight", round6(clamp01(Math.log1p(supportWeight) / Math.log(100D))));
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
            connectedRoleIds.add(roleId);
            connectedSkillIds.add(skillId);
        }

        nodes.entrySet().removeIf(entry -> {
            String type = text(entry.getValue().get("type"));
            if ("ROLE".equals(type)) {
                return !connectedRoleIds.contains(entry.getKey());
            }
            if ("SKILL".equals(type)) {
                return !connectedSkillIds.contains(entry.getKey());
            }
            return false;
        });

        long roleCount = nodes.values().stream()
                .filter(node -> "ROLE".equals(text(node.get("type"))))
                .count();
        long skillCount = nodes.values().stream()
                .filter(node -> "SKILL".equals(text(node.get("type"))))
                .count();
        long requiredCount = links.stream()
                .filter(link -> "REQUIRED".equals(text(link.get("type"))))
                .count();
        long bonusCount = links.stream()
                .filter(link -> "BONUS".equals(text(link.get("type"))))
                .count();
        long mentionedCount = links.stream()
                .filter(link -> "MENTIONED".equals(text(link.get("type"))))
                .count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("roleCount", roleCount);
        summary.put("skillCount", skillCount);
        summary.put("nodeCount", nodes.size());
        summary.put("linkCount", links.size());
        summary.put("requiredCount", requiredCount);
        summary.put("bonusCount", bonusCount);
        summary.put("mentionedCount", mentionedCount);

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("techStack", safe(stack));
        filters.put("level", safe(level));
        filters.put("limit", edgeLimit);
        filters.put("minEvidence", evidenceThreshold);

        long snapshotVersion = number(snapshot.get("processedCount")).longValue();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", new ArrayList<>(nodes.values()));
        result.put("links", links);
        result.put("summary", summary);
        result.put("stacks", List.of());
        result.put("filters", filters);
        result.put("palette", GRAPH_PALETTE);
        result.put("source", "MYSQL_GOVERNED_MILLION_JD");
        result.put("snapshotVersion", snapshotVersion);
        result.put("generatedAt", Instant.now().toString());
        result.put("stale", false);
        return result;
    }

    public List<Map<String, Object>> roles() {
        return store.list(
                "SELECT id,role_name,tech_stack,level_name,definition,confidence,version " +
                        "FROM job_role WHERE status='PUBLISHED' ORDER BY confidence DESC,role_name",
                Map.of()
        );
    }

    private Map<String, Object> emptyGraph(
            String stack,
            String level,
            int limit,
            int minEvidence,
            Map<String, Object> snapshot
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("roleCount", 0);
        summary.put("skillCount", 0);
        summary.put("nodeCount", 0);
        summary.put("linkCount", 0);
        summary.put("requiredCount", 0);
        summary.put("bonusCount", 0);
        summary.put("mentionedCount", 0);

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("techStack", safe(stack));
        filters.put("level", safe(level));
        filters.put("limit", limit);
        filters.put("minEvidence", minEvidence);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", List.of());
        result.put("links", List.of());
        result.put("summary", summary);
        result.put("stacks", List.of());
        result.put("filters", filters);
        result.put("palette", GRAPH_PALETTE);
        result.put("source", "MYSQL_GOVERNED_MILLION_JD");
        result.put("snapshotVersion", number(snapshot.get("processedCount")).longValue());
        result.put("generatedAt", Instant.now().toString());
        result.put("stale", false);
        return result;
    }

    private Map<String, Object> withWarning(Map<String, Object> payload, String warning) {
        Map<String, Object> result = new LinkedHashMap<>(payload);
        result.put("stale", true);
        result.put("warning", warning);
        return result;
    }

    private void trimGraphCache() {
        while (graphCache.size() > MAX_GRAPH_CACHE_ENTRIES) {
            GraphCacheKey first = graphCache.keySet().iterator().next();
            graphCache.remove(first);
        }
    }

    private Map<String, Object> baseNode(
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
        node.put("stack", safeValue(stack, "未分类"));
        node.put("meta", safeValue(meta, "未标注"));
        return node;
    }

    private Map<String, Object> sizeOption(
            String value,
            String label,
            int limit,
            String description
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", value);
        result.put("label", label);
        result.put("limit", limit);
        result.put("description", description);
        return result;
    }

    private String roleKey(String roleName, String stack, String level) {
        return safe(roleName)
                + "\u0000"
                + safeValue(stack, "未分类")
                + "\u0000"
                + safeValue(level, "未标注");
    }

    private int levelOrder(String value) {
        String normalized = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);

        if (normalized.contains("实习")) {
            return 0;
        }
        if (normalized.contains("初级") || normalized.contains("junior")) {
            return 1;
        }
        if (normalized.contains("中级") || normalized.contains("middle")) {
            return 2;
        }
        if (normalized.contains("高级") || normalized.contains("senior")) {
            return 3;
        }
        if (normalized.contains("专家") || normalized.contains("lead") || normalized.contains("principal")) {
            return 4;
        }
        if (normalized.contains("未标注")) {
            return 99;
        }
        return 20;
    }

    private String relationLabel(String type) {
        return switch (type) {
            case "REQUIRED" -> "必备技能";
            case "BONUS" -> "加分技能";
            default -> "相关技能";
        };
    }

    private String quoted(String table) {
        return "`" + table + "`";
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeValue(String value, String fallback) {
        String result = safe(value);
        return result.isBlank() ? fallback : result;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static double clamp01(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000D) / 1_000_000D;
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

    private record OptionsCache(long createdAtMillis, Map<String, Object> payload) {
    }

    private record GraphCacheKey(String stack, String level, int limit, int minEvidence) {
    }

    private record GraphCacheEntry(long snapshotVersion, String generatedAt, Map<String, Object> payload) {
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
