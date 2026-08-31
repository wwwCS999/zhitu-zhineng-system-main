package com.zhitu.service;

import com.zhitu.common.TextUtils;
import com.zhitu.dto.Requests;
import com.zhitu.repository.RawDatabaseClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 治理后 JD 的人工编辑服务。
 *
 * V7 原则：
 * 1. 原始 dataset_job_raw 永远只读；
 * 2. 人工编辑只作用于 zhitu_* 派生分析层；
 * 3. 人工数据管理与百万治理任务状态完全解耦；
 * 4. 无论治理任务 RUNNING / PAUSED / COMPLETED / IDLE，只要 MySQL 可连接，已治理 JD 都可查询和修改；
 * 5. 人工修改会写 manual_modified / origin_type=MANUAL，后续自动治理不得覆盖人工结果；
 * 6. 所有人工操作写入 zhitu_manual_edit_log，保证可追溯。
 */
@Service
public class GovernedJobEditService {

    public static final String EDIT_LOG_TABLE = "zhitu_manual_edit_log";

    private final RawDatabaseClient raw;
    private final RawJobGovernanceService governance;
    private final MassSkillDictionary dictionary;
    private final AtomicBoolean editSchemaReady = new AtomicBoolean(false);

    public GovernedJobEditService(
            RawDatabaseClient raw,
            RawJobGovernanceService governance,
            MassSkillDictionary dictionary
    ) {
        this.raw = raw;
        this.governance = governance;
        this.dictionary = dictionary;
    }

    /**
     * 永久可用的已治理 JD 数据管理列表。
     * 不检查治理任务是否正在运行，也不调用 assertReadyForAnalysis()。
     */
    public Map<String, Object> listJobs(
            int page,
            int size,
            Integer year,
            String keyword,
            String state
    ) {
        ensureEditSchema();

        int safePage = Math.max(1, page);
        int safeSize = Math.max(10, Math.min(size, 100));
        int offset = (safePage - 1) * safeSize;

        String normalizedState = state == null ? "ACTIVE" : state.trim().toUpperCase(Locale.ROOT);
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();

        switch (normalizedState) {
            case "ALL" -> {
                // 不附加删除状态条件。
            }
            case "VALID" -> where.append(" AND is_deleted=0 AND valid_for_analysis=1 ");
            case "LOW_QUALITY" -> where.append(" AND is_deleted=0 AND valid_for_analysis=0 ");
            case "MANUAL" -> where.append(" AND is_deleted=0 AND manual_modified=1 ");
            case "DELETED" -> where.append(" AND is_deleted=1 ");
            default -> where.append(" AND is_deleted=0 ");
        }

        if (year != null && year >= 2000 && year <= 2100) {
            where.append(" AND published_year=? ");
            args.add(year);
        }

        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            where.append(
                    " AND (title_standard LIKE ? OR title_raw LIKE ? OR company LIKE ? OR city LIKE ? OR tech_stack LIKE ?) "
            );
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }

        String table = "`" + RawJobGovernanceService.GOVERNED_TABLE + "`";
        long total = raw.scalarLong(
                "SELECT COUNT(*) FROM " + table + where,
                args.toArray()
        );

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(offset);

        List<Map<String, Object>> items = raw.list(
                "SELECT raw_job_id,title_raw,title_standard,company,city,published_at,published_year," +
                        "tech_stack,level_name,quality_score,duplicate_group,duplicate_weight,skill_count," +
                        "valid_for_analysis,governance_status,is_deleted,manual_modified,manual_modified_at," +
                        "deleted_at,governed_at " +
                        "FROM " + table + where +
                        " ORDER BY raw_job_id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray()
        );

        long pages = total == 0 ? 0 : (long) Math.ceil(total / (double) safeSize);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("total", total);
        result.put("totalPages", pages);
        result.put("year", year);
        result.put("keyword", keyword == null ? "" : keyword.trim());
        result.put("state", normalizedState);
        result.put("editable", true);
        result.put("independentFromGovernanceTask", true);
        return result;
    }

    public Map<String, Object> detail(long rawJobId) {
        ensureEditSchema();
        Map<String, Object> job = raw.one(
                "SELECT * FROM `" + RawJobGovernanceService.GOVERNED_TABLE + "` " +
                        "WHERE raw_job_id=? AND is_deleted=0",
                rawJobId
        );
        List<Map<String, Object>> skills = raw.list(
                "SELECT id,skill_name,tech_stack,category,requirement_type,confidence,evidence_text,origin_type,created_at " +
                        "FROM `" + RawJobGovernanceService.SKILL_TABLE + "` WHERE raw_job_id=? " +
                        "ORDER BY CASE requirement_type WHEN 'REQUIRED' THEN 1 WHEN 'BONUS' THEN 2 WHEN 'PREFERRED' THEN 2 ELSE 3 END," +
                        "confidence DESC,skill_name",
                rawJobId
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job", job);
        result.put("skills", skills);
        result.put("rawSourcePreserved", true);
        result.put("editable", true);
        return result;
    }

    public Map<String, Object> updateJob(long rawJobId, Requests.GovernedJobUpdate request) {
        ensureEditSchema();
        Map<String, Object> before = raw.one(
                "SELECT * FROM `" + RawJobGovernanceService.GOVERNED_TABLE + "` WHERE raw_job_id=? AND is_deleted=0",
                rawJobId
        );

        String title = pick(request.titleStandard(), before.get("title_standard"));
        String company = pick(request.company(), before.get("company"));
        String city = pick(request.city(), before.get("city"));
        String stack = pick(request.techStack(), before.get("tech_stack"));
        String level = pick(request.levelName(), before.get("level_name"));
        String description = request.descriptionClean() == null
                ? text(before.get("description_clean"))
                : request.descriptionClean().trim();
        Integer year = request.publishedYear() == null
                ? nullableInt(before.get("published_year"))
                : request.publishedYear();
        if (year != null && (year < 2000 || year > 2100)) {
            throw new IllegalArgumentException("发布年份必须在 2000~2100 之间");
        }
        boolean valid = request.validForAnalysis() == null
                ? number(before.get("valid_for_analysis")) == 1
                : request.validForAnalysis();

        String contentHash = TextUtils.sha256(title + "\n" + description);
        String templateHash = TextUtils.sha256(description.toLowerCase(Locale.ROOT)
                .replaceAll("\\d+(?:\\.\\d+)?", "#")
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff+#.]", " ")
                .replaceAll("\\s+", " ")
                .trim());

        raw.update(
                "UPDATE `" + RawJobGovernanceService.GOVERNED_TABLE + "` SET " +
                        "title_standard=?,company=?,city=?,published_year=?,tech_stack=?,level_name=?,description_clean=?," +
                        "content_hash=?,template_hash=?,duplicate_group=NULL,duplicate_weight=1," +
                        "valid_for_analysis=?,governance_status='MANUAL_EDITED',manual_modified=1,manual_modified_at=NOW() " +
                        "WHERE raw_job_id=? AND is_deleted=0",
                title, company, city, year, stack, level, description,
                contentHash, templateHash, valid ? 1 : 0, rawJobId
        );
        log(rawJobId, "UPDATE_JOB", "人工修改治理后 JD 字段");
        return detail(rawJobId);
    }

    /**
     * 用户界面的“删除 JD”是从分析层软删除：保留 dataset_job_raw，不破坏原始证据。
     */
    public Map<String, Object> deleteJob(long rawJobId) {
        ensureEditSchema();
        raw.one(
                "SELECT raw_job_id FROM `" + RawJobGovernanceService.GOVERNED_TABLE + "` WHERE raw_job_id=? AND is_deleted=0",
                rawJobId
        );
        raw.update(
                "UPDATE `" + RawJobGovernanceService.GOVERNED_TABLE + "` SET " +
                        "is_deleted=1,valid_for_analysis=0,governance_status='DELETED_BY_USER'," +
                        "manual_modified=1,manual_modified_at=NOW(),deleted_at=NOW() WHERE raw_job_id=?",
                rawJobId
        );
        // 派生技能从分析层移除；原始 JD 仍完整保留。
        raw.update(
                "DELETE FROM `" + RawJobGovernanceService.SKILL_TABLE + "` WHERE raw_job_id=?",
                rawJobId
        );
        log(rawJobId, "DELETE_JOB", "从治理分析层删除 JD；dataset_job_raw 原始记录保留");
        return Map.of(
                "rawJobId", rawJobId,
                "deleted", true,
                "rawSourcePreserved", true
        );
    }

    public Map<String, Object> addSkill(long rawJobId, Requests.GovernedSkillCreate request) {
        ensureEditSchema();
        raw.one(
                "SELECT raw_job_id FROM `" + RawJobGovernanceService.GOVERNED_TABLE + "` WHERE raw_job_id=? AND is_deleted=0",
                rawJobId
        );

        String requested = request.skillName().trim();
        if (requested.isBlank()) throw new IllegalArgumentException("技能名称不能为空");

        MassSkillDictionary.SkillDef known = dictionary.dictionary().values().stream()
                .filter(item -> item.canonical().equalsIgnoreCase(requested)
                        || item.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(requested)))
                .findFirst()
                .orElse(null);
        String canonical = known == null ? requested : known.canonical();
        String stack = nonBlank(request.techStack())
                ? request.techStack().trim()
                : known == null ? "人工补充" : known.stack();
        String category = nonBlank(request.category())
                ? request.category().trim()
                : known == null ? "人工能力项" : known.category();
        String type = normalizeRequirementType(request.requirementType());
        double confidence = request.confidence() == null
                ? 1D
                : Math.max(0.1D, Math.min(1D, request.confidence()));

        raw.update(
                "INSERT INTO `" + RawJobGovernanceService.SKILL_TABLE + "`(" +
                        "raw_job_id,skill_name,tech_stack,category,requirement_type,confidence,evidence_text,origin_type" +
                        ") VALUES(?,?,?,?,?,?,?,'MANUAL') " +
                        "ON DUPLICATE KEY UPDATE tech_stack=VALUES(tech_stack),category=VALUES(category)," +
                        "requirement_type=VALUES(requirement_type),confidence=VALUES(confidence)," +
                        "evidence_text=VALUES(evidence_text),origin_type='MANUAL'",
                rawJobId, canonical, stack, category, type, confidence, "人工审核补充技能"
        );
        refreshSkillCount(rawJobId);
        log(rawJobId, "ADD_SKILL", "人工添加能力项：" + canonical + " / " + type);
        return detail(rawJobId);
    }

    public Map<String, Object> deleteSkill(long rawJobId, long skillId) {
        ensureEditSchema();
        Map<String, Object> skill = raw.one(
                "SELECT id,skill_name FROM `" + RawJobGovernanceService.SKILL_TABLE + "` WHERE id=? AND raw_job_id=?",
                skillId, rawJobId
        );
        raw.update(
                "DELETE FROM `" + RawJobGovernanceService.SKILL_TABLE + "` WHERE id=? AND raw_job_id=?",
                skillId, rawJobId
        );
        refreshSkillCount(rawJobId);
        log(rawJobId, "DELETE_SKILL", "人工删除能力项：" + text(skill.get("skill_name")));
        return detail(rawJobId);
    }

    private void refreshSkillCount(long rawJobId) {
        long count = raw.scalarLong(
                "SELECT COUNT(*) FROM `" + RawJobGovernanceService.SKILL_TABLE + "` WHERE raw_job_id=?",
                rawJobId
        );
        raw.update(
                "UPDATE `" + RawJobGovernanceService.GOVERNED_TABLE + "` SET skill_count=?," +
                        "manual_modified=1,governance_status='MANUAL_EDITED',manual_modified_at=NOW() WHERE raw_job_id=?",
                count, rawJobId
        );
    }

    /**
     * 编辑表结构只初始化一次，避免每次分页查询都反复访问 information_schema。
     */
    public void ensureEditSchema() {
        if (editSchemaReady.get()) return;
        synchronized (editSchemaReady) {
            if (editSchemaReady.get()) return;

            governance.ensureSchema();
            String jobTable = RawJobGovernanceService.GOVERNED_TABLE;
            String skillTable = RawJobGovernanceService.SKILL_TABLE;
            if (!raw.columnExists(jobTable, "is_deleted")) {
                raw.update("ALTER TABLE `" + jobTable + "` ADD COLUMN is_deleted TINYINT(1) NOT NULL DEFAULT 0");
            }
            if (!raw.columnExists(jobTable, "manual_modified")) {
                raw.update("ALTER TABLE `" + jobTable + "` ADD COLUMN manual_modified TINYINT(1) NOT NULL DEFAULT 0");
            }
            if (!raw.columnExists(jobTable, "manual_modified_at")) {
                raw.update("ALTER TABLE `" + jobTable + "` ADD COLUMN manual_modified_at DATETIME NULL");
            }
            if (!raw.columnExists(jobTable, "deleted_at")) {
                raw.update("ALTER TABLE `" + jobTable + "` ADD COLUMN deleted_at DATETIME NULL");
            }
            if (!raw.columnExists(skillTable, "origin_type")) {
                raw.update("ALTER TABLE `" + skillTable + "` ADD COLUMN origin_type VARCHAR(30) NOT NULL DEFAULT 'AUTO'");
            }
            raw.update(
                    "CREATE TABLE IF NOT EXISTS `" + EDIT_LOG_TABLE + "`(" +
                            "id BIGINT PRIMARY KEY AUTO_INCREMENT,raw_job_id BIGINT NOT NULL,action_type VARCHAR(40)," +
                            "detail_text VARCHAR(1200),created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                            "INDEX idx_zhitu_edit_job(raw_job_id),INDEX idx_zhitu_edit_time(created_at)" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
            editSchemaReady.set(true);
        }
    }

    private void log(long rawJobId, String action, String detail) {
        raw.update(
                "INSERT INTO `" + EDIT_LOG_TABLE + "`(raw_job_id,action_type,detail_text) VALUES(?,?,?)",
                rawJobId, action, detail
        );
    }

    private String normalizeRequirementType(String type) {
        String value = type == null ? "REQUIRED" : type.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "BONUS", "PREFERRED" -> "BONUS";
            case "MENTIONED" -> "MENTIONED";
            default -> "REQUIRED";
        };
    }

    private String pick(String requested, Object oldValue) {
        return requested == null ? text(oldValue) : requested.trim();
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private Integer nullableInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }
}
