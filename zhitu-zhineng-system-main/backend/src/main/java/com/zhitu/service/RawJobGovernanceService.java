package com.zhitu.service;

import com.zhitu.common.TextUtils;
import com.zhitu.repository.RawDatabaseClient;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 对 dataset_job_raw 的百万级 JD 做连续、可暂停、可恢复的数据治理。
 *
 * 核心原则：
 * 1. 先锁定 2026 年 1000 条 holdout；
 * 2. 全量治理时严格排除 holdout，防止最终测试集泄漏；
 * 3. 使用 keyset pagination 按 raw_id 顺序批处理，不一次性把百万行载入内存；
 * 4. 规则清洗/标准化/技能抽取可复现，全量阶段不逐条调用 LLM；
 * 5. 原始 dataset_job_raw 永不 UPDATE/DELETE，只写 zhitu_* 派生表；
 * 6. 所有原始记录都保留，重复模板只降权，不直接删除。
 */
@Service
public class RawJobGovernanceService {

    public static final String GOVERNED_TABLE = "zhitu_governed_job";
    public static final String SKILL_TABLE = "zhitu_governed_job_skill";
    public static final String ISSUE_TABLE = "zhitu_governance_issue";
    public static final String GOVERNANCE_RUN_TABLE = "zhitu_governance_run";
    public static final String DUP_CLUSTER_TABLE = "zhitu_duplicate_cluster";

    private static final Pattern HTML = Pattern.compile("(?is)<script.*?</script>|<style.*?</style>|<[^>]+>");
    private static final Pattern CONTACT = Pattern.compile(
            "(?i)(招聘热线|联系电话|联系方式|联系人|邮箱|email|微信|公众号)\\s*[:：]?\\s*\\S+"
    );
    private static final Pattern AD = Pattern.compile(
            "(?i)(点击申请|立即沟通|立即投递|收藏职位|举报职位|职位亮点|福利待遇[:：]?|公司福利[:：]?)"
    );
    private static final Pattern TITLE_BRACKET = Pattern.compile("[（(【\\[].{0,40}?[）)】\\]]");
    private static final Pattern TITLE_NOISE = Pattern.compile(
            "(?i)(急聘|诚聘|高薪|直招|校招|社招|招聘|五险一金|双休|包吃住|\\d+(?:\\.\\d+)?k[-~—]?\\d*(?:\\.\\d+)?k?)"
    );

    private final RawDatabaseClient raw;
    private final RawJobSchemaService schemaService;
    private final TemporalDatasetService temporalDatasetService;
    private final MassSkillDictionary skillDictionary;
    private final int defaultBatchSize;
    private final double minimumQuality;
    private final int analysisMinGovernedRows;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "zhitu-million-jd-governance");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);
    private final AtomicBoolean interruptedRunsNormalized = new AtomicBoolean(false);

    public RawJobGovernanceService(
            RawDatabaseClient raw,
            RawJobSchemaService schemaService,
            TemporalDatasetService temporalDatasetService,
            MassSkillDictionary skillDictionary,
            @Value("${app.raw-database.governance-batch-size:100}") int defaultBatchSize,
            @Value("${app.raw-database.minimum-quality:0.45}") double minimumQuality,
            @Value("${app.raw-database.analysis-min-governed-rows:100}") int analysisMinGovernedRows
    ) {
        this.raw = raw;
        this.schemaService = schemaService;
        this.temporalDatasetService = temporalDatasetService;
        this.skillDictionary = skillDictionary;
        this.defaultBatchSize = Math.max(100, Math.min(defaultBatchSize, 5000));
        this.minimumQuality = Math.max(0.1, Math.min(minimumQuality, 0.95));
        this.analysisMinGovernedRows = Math.max(100, analysisMinGovernedRows);
    }

    public Map<String, Object> overview() {
        assertSourceReady();
        ensureSchema();
        normalizeInterruptedRuns();

        long rawTotal = raw.scalarLong("SELECT COUNT(*) FROM " + raw.quotedRawTable());
        long holdout = raw.scalarLong(
                "SELECT COUNT(*) FROM `" + TemporalDatasetService.HOLDOUT_TABLE + "` WHERE holdout_year = ?",
                temporalDatasetService.defaultHoldoutYear()
        );
        long target = Math.max(0, rawTotal - holdout);
        long governed = raw.scalarLong("SELECT COUNT(*) FROM `" + GOVERNED_TABLE + "`");
        long valid = raw.scalarLong("SELECT COUNT(*) FROM `" + GOVERNED_TABLE + "` WHERE valid_for_analysis = 1 AND is_deleted=0");
        long duplicates = raw.scalarLong("SELECT COUNT(*) FROM `" + GOVERNED_TABLE + "` WHERE duplicate_group IS NOT NULL AND is_deleted=0");
        long lowQuality = raw.scalarLong("SELECT COUNT(*) FROM `" + GOVERNED_TABLE + "` WHERE quality_score < ? AND is_deleted=0", minimumQuality);
        long deletedRows = raw.scalarLong("SELECT COUNT(*) FROM `" + GOVERNED_TABLE + "` WHERE is_deleted=1");
        long skillRelations = raw.scalarLong("SELECT COUNT(*) FROM `" + SKILL_TABLE + "` s JOIN `" + GOVERNED_TABLE + "` g ON g.raw_job_id=s.raw_job_id WHERE g.is_deleted=0");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connected", true);
        result.put("rawTable", raw.rawTable());
        result.put("rawTotal", rawTotal);
        result.put("holdoutYear", temporalDatasetService.defaultHoldoutYear());
        result.put("holdoutTarget", temporalDatasetService.defaultHoldoutSize());
        result.put("holdoutRows", holdout);
        result.put("trainingTarget", target);
        result.put("governedRows", governed);
        result.put("remainingRows", Math.max(0, target - governed));
        result.put("validRows", valid);
        result.put("duplicateRows", duplicates);
        result.put("lowQualityRows", lowQuality);
        result.put("deletedRows", deletedRows);
        result.put("skillRelations", skillRelations);
        boolean snapshotReady = governed >= analysisMinGovernedRows && valid > 0;
        boolean fullGovernanceComplete = holdout == temporalDatasetService.defaultHoldoutSize() && governed >= target;
        long snapshotVersion = governed < analysisMinGovernedRows
                ? 0L
                : (governed / analysisMinGovernedRows) * (long) analysisMinGovernedRows;

        result.put("progress", target == 0 ? 0D : Math.min(1D, governed / (double) target));
        // V10：达到最少 100 条治理记录后即可使用各分析模块；全量完成状态单独返回。
        result.put("readyForAnalysis", snapshotReady);
        result.put("snapshotReady", snapshotReady);
        result.put("fullGovernanceComplete", fullGovernanceComplete);
        result.put("analysisMinGovernedRows", analysisMinGovernedRows);
        result.put("snapshotVersion", snapshotVersion);
        result.put("analysisScope", fullGovernanceComplete ? "FULL_GOVERNANCE" : "PARTIAL_SNAPSHOT");
        result.put("running", running.get());
        result.put("minimumQuality", minimumQuality);
        result.put("schema", schemaService.describe());
        result.put("latestRun", latestRun());
        result.put("quality", qualitySummary());
        return result;
    }

    /**
     * 轻量级治理进度快照。
     *
     * 与 overview() 不同，这里不扫描百万原始表，也不计算全量质量统计；
     * 只读取 zhitu_governance_run 的最近一条运行记录和小规模 holdout 表，
     * 专门供前端每 2 秒轮询，让“百万 JD 治理”板块在页面打开后立即可见并快速更新。
     */
    public Map<String, Object> progressSnapshot() {
        // V8：高频进度轮询不再先额外执行一次 SELECT 1。
        // 直接读取轻量运行表即可同时完成“探活 + 取进度”，减少一次连接池借用。
        ensureSchema();
        normalizeInterruptedRuns();

        Map<String, Object> run = latestRun();
        long target = number(run.get("total_target")).longValue();
        long processed = number(run.get("processed_count")).longValue();
        long valid = number(run.get("valid_count")).longValue();
        long lastRawId = number(run.get("last_raw_id")).longValue();
        long failed = number(run.get("failed_count")).longValue();

        long holdout = raw.scalarLong(
                "SELECT COUNT(*) FROM `" + TemporalDatasetService.HOLDOUT_TABLE + "` WHERE holdout_year = ?",
                temporalDatasetService.defaultHoldoutYear()
        );

        String status = text(run.get("status"));
        if (status.isBlank()) status = running.get() ? "RUNNING" : "IDLE";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connected", true);
        result.put("rawTable", raw.rawTable());
        result.put("status", status);
        result.put("running", running.get());
        result.put("pauseRequested", pauseRequested.get());
        result.put("processedCount", processed);
        result.put("validCount", valid);
        result.put("failedCount", failed);
        result.put("lastRawId", lastRawId);
        result.put("targetCount", target);
        result.put("holdoutRows", holdout);
        result.put("holdoutTarget", temporalDatasetService.defaultHoldoutSize());
        result.put("rawTotalApprox", target > 0 ? target + holdout : 0);
        result.put("remainingCount", target > 0 ? Math.max(0, target - processed) : 0);
        boolean snapshotReady = processed >= analysisMinGovernedRows && valid > 0;
        boolean fullGovernanceComplete = target > 0
                && processed >= target
                && holdout == temporalDatasetService.defaultHoldoutSize();
        long snapshotVersion = processed < analysisMinGovernedRows
                ? 0L
                : (processed / analysisMinGovernedRows) * (long) analysisMinGovernedRows;

        result.put("progress", target > 0 ? Math.min(1D, processed / (double) target) : 0D);
        result.put("readyForAnalysis", snapshotReady);
        result.put("snapshotReady", snapshotReady);
        result.put("fullGovernanceComplete", fullGovernanceComplete);
        result.put("analysisMinGovernedRows", analysisMinGovernedRows);
        result.put("snapshotVersion", snapshotVersion);
        result.put("analysisScope", fullGovernanceComplete ? "FULL_GOVERNANCE" : "PARTIAL_SNAPSHOT");
        result.put("currentStage", text(run.get("current_stage")));
        result.put("batchSize", number(run.get("batch_size")).intValue());
        result.put("startedAt", run.get("started_at"));
        result.put("finishedAt", run.get("finished_at"));
        result.put("errorMessage", text(run.get("error_message")));
        result.put("runId", run.getOrDefault("run_id", ""));
        return result;
    }

    public synchronized Map<String, Object> start(boolean reset, Integer requestedBatchSize) {
        assertSourceReady();
        ensureSchema();
        normalizeInterruptedRuns();

        if (running.get()) {
            return statusMessage("RUNNING", "治理任务已经在运行，不需要重复启动");
        }

        // 先固定最终测试集。prepareHoldout 已改为通过真实字段映射获取年份，不再要求 published_at。
        temporalDatasetService.prepareDefaultHoldout(false);

        if (reset) {
            resetDerivedData();
        }

        // 安全兜底：当前 holdout 永远不能留在训练派生表中。
        purgeHoldoutFromDerived();

        // V8：这些百万级 COUNT 只在“开始/恢复任务”时执行一次，不再每个批次重复执行。
        long rawTotal = raw.scalarLong("SELECT COUNT(*) FROM " + raw.quotedRawTable());
        long holdout = raw.scalarLong(
                "SELECT COUNT(*) FROM `" + TemporalDatasetService.HOLDOUT_TABLE + "` WHERE holdout_year = ?",
                temporalDatasetService.defaultHoldoutYear()
        );
        long target = Math.max(0, rawTotal - holdout);

        Map<String, Object> checkpoint = latestCheckpoint();
        long lastRawId = number(checkpoint.get("last_raw_id")).longValue();
        long already = number(checkpoint.get("processed_count")).longValue();

        // 老版本项目可能已有治理结果但没有可靠 run 断点；这种情况下仅在启动时回退统计一次。
        if (checkpoint.isEmpty() && raw.tableExists(GOVERNED_TABLE)) {
            already = raw.scalarLong("SELECT COUNT(*) FROM `" + GOVERNED_TABLE + "`");
        }

        // valid_count 可能因为治理暂停期间发生人工编辑而变化，所以恢复时只精确校准一次。
        long validAlready = raw.scalarLong(
                "SELECT COUNT(*) FROM `" + GOVERNED_TABLE + "` WHERE valid_for_analysis=1 AND is_deleted=0"
        );

        if (already >= target && target > 0) {
            finalizeDuplicateWeights();
            return statusMessage("COMPLETED", "训练池已经全部治理完成，可以直接进入年度分析");
        }

        int batchSize = requestedBatchSize == null
                ? defaultBatchSize
                : Math.max(100, Math.min(requestedBatchSize, 5000));

        String runId = UUID.randomUUID().toString();

        raw.update(
                "INSERT INTO `" + GOVERNANCE_RUN_TABLE + "`(" +
                        "run_id,status,total_target,processed_count,success_count,failed_count,valid_count," +
                        "last_raw_id,current_stage,batch_size,started_at" +
                        ") VALUES(?,?,?,?,?,?,?,?,?,?,NOW())",
                runId, "RUNNING", target, already, already, 0, validAlready,
                lastRawId, "字段映射与批处理准备", batchSize
        );

        pauseRequested.set(false);
        running.set(true);
        interruptedRunsNormalized.set(true);

        long initialProcessed = already;
        long initialValid = validAlready;
        executor.submit(() -> processRun(
                runId,
                target,
                batchSize,
                lastRawId,
                initialProcessed,
                initialValid
        ));

        Map<String, Object> result = statusMessage("RUNNING", "已启动百万 JD 连续治理");
        result.put("runId", runId);
        result.put("targetRows", target);
        result.put("alreadyGoverned", already);
        result.put("validRows", validAlready);
        result.put("batchSize", batchSize);
        return result;
    }

    public Map<String, Object> pause() {
        if (!running.get()) {
            return statusMessage("IDLE", "当前没有正在运行的治理任务");
        }
        pauseRequested.set(true);
        return statusMessage("PAUSING", "已请求暂停，将在当前批次写入完成后安全停止");
    }

    public Map<String, Object> resume(Integer batchSize) {
        return start(false, batchSize);
    }

    public List<Map<String, Object>> runs(int limit) {
        ensureSchema();
        int safe = Math.max(1, Math.min(limit, 100));
        return raw.list(
                "SELECT * FROM `" + GOVERNANCE_RUN_TABLE + "` ORDER BY started_at DESC LIMIT " + safe
        );
    }

    public List<Map<String, Object>> samples(int limit) {
        ensureSchema();
        int safe = Math.max(1, Math.min(limit, 200));
        return raw.list(
                "SELECT raw_job_id,title_raw,title_standard,company,city,published_at,published_year," +
                        "tech_stack,level_name,quality_score,duplicate_group,duplicate_weight,skill_count," +
                        "valid_for_analysis,governance_status,manual_modified,manual_modified_at,governed_at " +
                        "FROM `" + GOVERNED_TABLE + "` WHERE is_deleted=0 ORDER BY raw_job_id DESC LIMIT " + safe
        );
    }

    public Map<String, Object> qualitySummary() {
        ensureSchema();
        return raw.one(
                "SELECT COUNT(*) AS total," +
                        "COALESCE(AVG(quality_score),0) AS avg_quality," +
                        "COALESCE(AVG(stale_score),0) AS avg_stale," +
                        "SUM(CASE WHEN duplicate_group IS NOT NULL THEN 1 ELSE 0 END) AS near_duplicates," +
                        "SUM(CASE WHEN quality_score < ? THEN 1 ELSE 0 END) AS low_quality," +
                        "SUM(CASE WHEN published_year IS NULL OR published_year < 2000 OR published_year > 2100 THEN 1 ELSE 0 END) AS missing_year," +
                        "SUM(CASE WHEN skill_count = 0 THEN 1 ELSE 0 END) AS no_skill " +
                        "FROM `" + GOVERNED_TABLE + "` WHERE is_deleted=0",
                minimumQuality
        );
    }

    /**
     * V10 分析快照准入：只要至少治理 analysisMinGovernedRows（默认 100）条，
     * 并且其中存在通过质量门控的有效记录，就允许总览、探新、验证、演化、图谱、
     * 匹配等模块基于“当前快照”运行。
     *
     * 全量治理是否完成由 fullGovernanceComplete 单独表示，不再阻塞阶段性分析。
     */
    public void assertReadyForAnalysis() {
        Map<String, Object> snapshot = analysisSnapshot();
        if (!Boolean.TRUE.equals(snapshot.get("snapshotReady"))) {
            long processed = number(snapshot.get("processedRows")).longValue();
            long valid = number(snapshot.get("validRows")).longValue();
            throw new IllegalStateException(
                    "当前解析快照尚不足以进行分析：已治理 " + processed + " 条，其中有效 " + valid +
                            " 条。至少完成 " + analysisMinGovernedRows +
                            " 条治理记录后即可使用总览、探新、验证、演化、图谱、匹配、学习和审核等模块。"
            );
        }
    }

    /**
     * 仅在确实需要最终全量结果时调用。普通页面分析不要使用该方法。
     */
    public void assertFullGovernanceComplete() {
        Map<String, Object> snapshot = analysisSnapshot();
        if (!Boolean.TRUE.equals(snapshot.get("fullGovernanceComplete"))) {
            throw new IllegalStateException(
                    "当前仍是阶段性快照：已治理 " + snapshot.get("processedRows") +
                            " / " + snapshot.get("targetRows") +
                            "。可以继续使用各分析模块，但最终比赛结果建议在全量治理完成后重新更新一次。"
            );
        }
    }

    /**
     * 轻量分析快照元数据。优先读取治理运行表，不扫描原始百万表。
     */
    public Map<String, Object> analysisSnapshot() {
        assertSourceReady();
        ensureSchema();
        normalizeInterruptedRuns();

        Map<String, Object> run = latestRun();
        long processed = number(run.get("processed_count")).longValue();
        long valid = number(run.get("valid_count")).longValue();
        long target = number(run.get("total_target")).longValue();

        // 兼容旧任务：若运行表没有进度，则只做一次治理派生表计数。
        if (processed <= 0 && raw.tableExists(GOVERNED_TABLE)) {
            processed = raw.scalarLong("SELECT COUNT(*) FROM `" + GOVERNED_TABLE + "`");
        }
        if (valid <= 0 && processed > 0 && raw.tableExists(GOVERNED_TABLE)) {
            valid = raw.scalarLong(
                    "SELECT COUNT(*) FROM `" + GOVERNED_TABLE + "` WHERE valid_for_analysis=1 AND is_deleted=0"
            );
        }

        long holdout = 0L;
        if (raw.tableExists(TemporalDatasetService.HOLDOUT_TABLE)) {
            holdout = raw.scalarLong(
                    "SELECT COUNT(*) FROM `" + TemporalDatasetService.HOLDOUT_TABLE + "` WHERE holdout_year = ?",
                    temporalDatasetService.defaultHoldoutYear()
            );
        }

        if (target <= 0) {
            // 仅在旧运行记录缺少 target 时才扫描一次原始表。
            target = Math.max(0L, raw.scalarLong("SELECT COUNT(*) FROM " + raw.quotedRawTable()) - holdout);
        }

        boolean snapshotReady = processed >= analysisMinGovernedRows && valid > 0;
        boolean fullGovernanceComplete = target > 0
                && processed >= target
                && holdout == temporalDatasetService.defaultHoldoutSize();
        long snapshotVersion = processed < analysisMinGovernedRows
                ? 0L
                : (processed / analysisMinGovernedRows) * (long) analysisMinGovernedRows;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processedRows", processed);
        result.put("validRows", valid);
        result.put("targetRows", target);
        result.put("remainingRows", Math.max(0L, target - processed));
        result.put("holdoutRows", holdout);
        result.put("holdoutTarget", temporalDatasetService.defaultHoldoutSize());
        result.put("snapshotReady", snapshotReady);
        result.put("readyForAnalysis", snapshotReady);
        result.put("fullGovernanceComplete", fullGovernanceComplete);
        result.put("analysisMinGovernedRows", analysisMinGovernedRows);
        result.put("snapshotVersion", snapshotVersion);
        result.put("analysisScope", fullGovernanceComplete ? "FULL_GOVERNANCE" : "PARTIAL_SNAPSHOT");
        result.put("runStatus", text(run.get("status")));
        result.put("runId", run.getOrDefault("run_id", ""));
        return result;
    }

    private void processRun(
            String runId,
            long target,
            int batchSize,
            long initialLastId,
            long initialProcessed,
            long initialValid
    ) {
        long lastId = initialLastId;
        long processed = initialProcessed;
        long valid = initialValid;
        long failed = 0;

        try {
            RawJobSchemaService.SchemaMapping mapping = schemaService.resolve();
            updateRun(
                    runId,
                    "RUNNING",
                    processed,
                    valid,
                    failed,
                    lastId,
                    "清洗、标准化、去噪、技能抽取"
            );

            while (!pauseRequested.get()) {
                long currentAfterId = lastId;
                List<Map<String, Object>> rows = withDatabaseRetry(
                        "读取下一批治理数据",
                        () -> fetchBatch(mapping, currentAfterId, batchSize)
                );
                if (rows.isEmpty()) break;

                BatchResult result = withDatabaseRetry(
                        "写入当前治理批次",
                        () -> governBatch(runId, rows)
                );
                lastId = result.lastRawId();

                // V8 核心优化：不再每处理一批就 COUNT 整张 zhitu_governed_job。
                // 每批只累加当前批次结果，数据库压力不会随着 50万/100万记录增长而越来越大。
                processed += rows.size();
                valid += result.valid();
                failed += result.failed();

                updateRun(
                        runId,
                        "RUNNING",
                        processed,
                        valid,
                        failed,
                        lastId,
                        "连续治理训练 JD"
                );
            }

            if (pauseRequested.get()) {
                updateRun(
                        runId,
                        "PAUSED",
                        processed,
                        valid,
                        failed,
                        lastId,
                        "用户暂停；下次可从断点继续"
                );
                return;
            }

            updateRun(
                    runId,
                    "RUNNING",
                    processed,
                    valid,
                    failed,
                    lastId,
                    "重复模板聚类与证据降权"
            );
            finalizeDuplicateWeights();

            // 全量治理完成后才做一次精确 COUNT，用于最终结果校准。
            long exactProcessed = raw.scalarLong("SELECT COUNT(*) FROM `" + GOVERNED_TABLE + "`");
            long exactValid = raw.scalarLong(
                    "SELECT COUNT(*) FROM `" + GOVERNED_TABLE + "` WHERE valid_for_analysis=1 AND is_deleted=0"
            );
            long duplicates = raw.scalarLong(
                    "SELECT COUNT(*) FROM `" + GOVERNED_TABLE + "` WHERE duplicate_group IS NOT NULL AND is_deleted=0"
            );

            raw.update(
                    "UPDATE `" + GOVERNANCE_RUN_TABLE + "` SET status='COMPLETED',processed_count=?,success_count=?," +
                            "failed_count=?,valid_count=?,duplicate_count=?,current_stage='治理完成，可进入年度分析'," +
                            "finished_at=NOW() WHERE run_id=?",
                    exactProcessed, exactProcessed, failed, exactValid, duplicates, runId
            );
        } catch (Exception e) {
            try {
                raw.update(
                        "UPDATE `" + GOVERNANCE_RUN_TABLE + "` SET status='FAILED',current_stage='治理异常'," +
                                "error_message=?,finished_at=NOW() WHERE run_id=?",
                        abbreviate(e.getMessage(), 1900), runId
                );
            } catch (Exception ignored) {
                // 如果异常本身就是 MySQL 短暂不可用，避免记录失败状态时的二次异常覆盖原始问题。
            }
        } finally {
            running.set(false);
            pauseRequested.set(false);
        }
    }

    /**
     * 对百万治理过程中的瞬时数据库错误进行有限重试。
     * governBatch 已经使用事务，失败会整体回滚，因此安全重试不会产生半批次。
     */
    private <T> T withDatabaseRetry(String operation, Supplier<T> action) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                last = e;

                if (pauseRequested.get()) {
                    throw e;
                }

                if (attempt >= 4) {
                    break;
                }

                long sleepMs = 1_000L * attempt;
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(operation + "重试被中断", interrupted);
                }
            }
        }

        throw new IllegalStateException(
                operation + "连续失败，已自动重试 4 次。可安全暂停后稍后继续。",
                last
        );
    }

    private List<Map<String, Object>> fetchBatch(
            RawJobSchemaService.SchemaMapping mapping,
            long afterId,
            int batchSize
    ) {
        String id = "r.`" + mapping.id().replace("`", "``") + "`";
        String sql = "SELECT " + schemaService.selectProjection("r") +
                " FROM " + raw.quotedRawTable() + " r " +
                "LEFT JOIN `" + TemporalDatasetService.HOLDOUT_TABLE + "` h " +
                "ON h.raw_job_id = " + id + " AND h.holdout_year = ? " +
                "WHERE " + id + " > ? AND h.raw_job_id IS NULL " +
                "ORDER BY " + id + " LIMIT " + batchSize;
        return raw.list(sql, temporalDatasetService.defaultHoldoutYear(), afterId);
    }

    private BatchResult governBatch(String runId, List<Map<String, Object>> rows) {
        List<Object[]> jobs = new ArrayList<>();
        List<Object[]> skills = new ArrayList<>();
        List<Object[]> issues = new ArrayList<>();
        long failed = 0;
        long validCount = 0;
        long lastRawId = 0;

        for (Map<String, Object> row : rows) {
            long rawId = number(row.get("raw_id")).longValue();
            lastRawId = Math.max(lastRawId, rawId);
            try {
                String titleRaw = text(row.get("raw_title"));
                String company = normalizeSimple(text(row.get("company")), 300);
                String description = cleanDescription(text(row.get("description")));
                String title = normalizeTitle(titleRaw);
                LocalDate publishedAt = toLocalDate(row.get("published_at"));
                Integer publishedYear = validYear(number(row.get("published_year")).intValue())
                        ? number(row.get("published_year")).intValue()
                        : publishedAt == null ? null : publishedAt.getYear();
                String city = normalizeSimple(text(row.get("city")), 120);
                String education = normalizeSimple(text(row.get("education")), 120);
                String experience = normalizeSimple(text(row.get("experience")), 200);
                String source = normalizeSimple(text(row.get("source_name")), 200);
                String industry = normalizeSimple(text(row.get("industry")), 200);
                Double salaryMin = decimal(row.get("salary_min"));
                Double salaryMax = decimal(row.get("salary_max"));

                List<MassSkillDictionary.SkillHit> hitList = skillDictionary.extract(title, description);
                String stack = inferStack(title + "\n" + description, hitList);
                String level = inferLevel(title + "\n" + description, experience);
                double quality = qualityScore(title, company, description, publishedYear, hitList.size());
                double stale = staleScore(publishedYear);
                boolean valid = !title.isBlank() && description.length() >= 20 &&
                        publishedYear != null && quality >= minimumQuality;
                if (valid) validCount++;

                String contentHash = TextUtils.sha256(title + "\n" + description);
                String templateHash = TextUtils.sha256(templateFingerprint(description));
                String status = valid ? "CLEANED" : "LOW_QUALITY";

                jobs.add(new Object[]{
                        rawId, runId, titleRaw, title, company, city, industry,
                        salaryMin, salaryMax, education, experience, source,
                        description, publishedAt == null ? null : Date.valueOf(publishedAt), publishedYear,
                        stack, level, contentHash, templateHash, quality, stale,
                        null, 1.0D, hitList.size(), valid ? 1 : 0, status
                });

                if (title.isBlank()) {
                    issues.add(issue(rawId, runId, "MISSING_FIELD", "title", "HIGH", "岗位名称为空或无法标准化"));
                }
                if (description.length() < 20) {
                    issues.add(issue(rawId, runId, "LOW_CONTENT", "description", "HIGH", "职位描述过短，无法形成可靠岗位证据"));
                }
                if (publishedYear == null) {
                    issues.add(issue(rawId, runId, "MISSING_YEAR", "published_year", "HIGH", "无法解析招聘发布日期/年份，不能进入年度实验"));
                }
                if (hitList.isEmpty()) {
                    issues.add(issue(rawId, runId, "NO_SKILL", "skills", "MEDIUM", "未命中当前技能词典，保留岗位但标记待扩词典/人工复核"));
                }

                String fullText = title + "\n" + description;
                for (MassSkillDictionary.SkillHit hit : hitList) {
                    skills.add(new Object[]{
                            rawId, hit.canonical(), hit.stack(), hit.category(),
                            skillDictionary.requirementType(fullText, hit.evidence()),
                            hit.confidence(), abbreviate(hit.evidence(), 900)
                    });
                }
            } catch (Exception e) {
                failed++;
                String title = text(row.get("raw_title"));
                jobs.add(new Object[]{
                        rawId, runId, title, normalizeTitle(title), "", "", "",
                        null, null, "", "", "", "", null, null,
                        "其他", "未知", TextUtils.sha256(title), TextUtils.sha256(title),
                        0D, 1D, null, 1D, 0, 0, "FAILED"
                });
                issues.add(issue(rawId, runId, "ROW_PROCESSING_ERROR", "row", "HIGH", abbreviate(e.getMessage(), 900)));
            }
        }

        String jobSql = "INSERT INTO `" + GOVERNED_TABLE + "`(" +
                "raw_job_id,run_id,title_raw,title_standard,company,city,industry,salary_min,salary_max," +
                "education,experience_text,source_name,description_clean,published_at,published_year,tech_stack," +
                "level_name,content_hash,template_hash,quality_score,stale_score,duplicate_group,duplicate_weight," +
                "skill_count,valid_for_analysis,governance_status,governed_at" +
                ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW()) " +
                // V7：自动治理允许刷新原始字段/运行信息，但绝不覆盖人工修订或人工删除后的业务结果。
                "ON DUPLICATE KEY UPDATE run_id=VALUES(run_id),title_raw=VALUES(title_raw)," +
                "title_standard=IF(manual_modified=1 OR is_deleted=1,title_standard,VALUES(title_standard))," +
                "company=IF(manual_modified=1 OR is_deleted=1,company,VALUES(company))," +
                "city=IF(manual_modified=1 OR is_deleted=1,city,VALUES(city))," +
                "industry=IF(manual_modified=1 OR is_deleted=1,industry,VALUES(industry))," +
                "salary_min=IF(manual_modified=1 OR is_deleted=1,salary_min,VALUES(salary_min))," +
                "salary_max=IF(manual_modified=1 OR is_deleted=1,salary_max,VALUES(salary_max))," +
                "education=IF(manual_modified=1 OR is_deleted=1,education,VALUES(education))," +
                "experience_text=IF(manual_modified=1 OR is_deleted=1,experience_text,VALUES(experience_text))," +
                "source_name=IF(manual_modified=1 OR is_deleted=1,source_name,VALUES(source_name))," +
                "description_clean=IF(manual_modified=1 OR is_deleted=1,description_clean,VALUES(description_clean))," +
                "published_at=IF(manual_modified=1 OR is_deleted=1,published_at,VALUES(published_at))," +
                "published_year=IF(manual_modified=1 OR is_deleted=1,published_year,VALUES(published_year))," +
                "tech_stack=IF(manual_modified=1 OR is_deleted=1,tech_stack,VALUES(tech_stack))," +
                "level_name=IF(manual_modified=1 OR is_deleted=1,level_name,VALUES(level_name))," +
                "content_hash=IF(manual_modified=1 OR is_deleted=1,content_hash,VALUES(content_hash))," +
                "template_hash=IF(manual_modified=1 OR is_deleted=1,template_hash,VALUES(template_hash))," +
                "quality_score=IF(manual_modified=1 OR is_deleted=1,quality_score,VALUES(quality_score))," +
                "stale_score=IF(manual_modified=1 OR is_deleted=1,stale_score,VALUES(stale_score))," +
                "skill_count=IF(manual_modified=1 OR is_deleted=1,skill_count,VALUES(skill_count))," +
                "valid_for_analysis=IF(manual_modified=1 OR is_deleted=1,valid_for_analysis,VALUES(valid_for_analysis))," +
                "governance_status=IF(manual_modified=1 OR is_deleted=1,governance_status,VALUES(governance_status))," +
                "governed_at=IF(manual_modified=1 OR is_deleted=1,governed_at,NOW())";
        // V8：一个治理批次只占用一个事务连接，岗位 / 技能 / 问题要么一起提交，要么一起回滚。
        // 这既减少连接池反复借还，也避免出现“岗位写完、技能没写完”的半批次。
        raw.inTransaction(() -> {
            raw.jdbc().batchUpdate(jobSql, jobs);

            if (!skills.isEmpty()) {
                String skillSql = "INSERT INTO `" + SKILL_TABLE + "`(" +
                        "raw_job_id,skill_name,tech_stack,category,requirement_type,confidence,evidence_text,origin_type" +
                        ") VALUES(?,?,?,?,?,?,?,'AUTO') ON DUPLICATE KEY UPDATE " +
                        // 人工技能优先级最高：重新治理时只能更新 AUTO 技能，不能覆盖 MANUAL 技能。
                        "tech_stack=IF(origin_type='MANUAL',tech_stack,VALUES(tech_stack))," +
                        "category=IF(origin_type='MANUAL',category,VALUES(category))," +
                        "requirement_type=IF(origin_type='MANUAL',requirement_type,VALUES(requirement_type))," +
                        "confidence=IF(origin_type='MANUAL',confidence,GREATEST(confidence,VALUES(confidence)))," +
                        "evidence_text=IF(origin_type='MANUAL',evidence_text,VALUES(evidence_text))";
                raw.jdbc().batchUpdate(skillSql, skills);
            }

            if (!issues.isEmpty()) {
                raw.jdbc().batchUpdate(
                        "INSERT INTO `" + ISSUE_TABLE + "`(" +
                                "raw_job_id,run_id,issue_type,field_name,severity,issue_message" +
                                ") VALUES(?,?,?,?,?,?) " +
                                "ON DUPLICATE KEY UPDATE run_id=VALUES(run_id),severity=VALUES(severity)," +
                                "issue_message=VALUES(issue_message),created_at=CURRENT_TIMESTAMP",
                        issues
                );
            }
        });

        return new BatchResult(lastRawId, failed, validCount);
    }

    private void finalizeDuplicateWeights() {
        ensureSchema();
        raw.update("TRUNCATE TABLE `" + DUP_CLUSTER_TABLE + "`");
        raw.update(
                "INSERT INTO `" + DUP_CLUSTER_TABLE + "`(template_hash,root_raw_job_id,member_count) " +
                        "SELECT template_hash,MIN(raw_job_id),COUNT(*) FROM `" + GOVERNED_TABLE + "` " +
                        "WHERE template_hash IS NOT NULL AND template_hash<>'' AND valid_for_analysis=1 AND is_deleted=0 " +
                        "GROUP BY template_hash HAVING COUNT(*)>1"
        );
        raw.update(
                "UPDATE `" + GOVERNED_TABLE + "` g LEFT JOIN `" + DUP_CLUSTER_TABLE + "` d " +
                        "ON d.template_hash=g.template_hash SET " +
                        "g.duplicate_group=CASE WHEN d.member_count>1 THEN CONCAT('TPL-',d.root_raw_job_id) ELSE NULL END," +
                        "g.duplicate_weight=CASE WHEN d.member_count>1 THEN GREATEST(0.15,1/SQRT(d.member_count)) ELSE 1 END," +
                        "g.governance_status=CASE " +
                        "WHEN g.manual_modified=1 THEN g.governance_status " +
                        "WHEN d.member_count>1 AND g.valid_for_analysis=1 THEN 'DUPLICATE_REVIEW' " +
                        "WHEN g.valid_for_analysis=1 THEN 'CLEANED' ELSE g.governance_status END " +
                        "WHERE g.is_deleted=0"
        );
    }

    public void ensureSchema() {
        if (schemaReady.get()) return;
        synchronized (schemaReady) {
            if (schemaReady.get()) return;
            temporalDatasetService.ensureExperimentSchema();
            raw.update(
                    "CREATE TABLE IF NOT EXISTS `" + GOVERNED_TABLE + "`(" +
                            "raw_job_id BIGINT PRIMARY KEY," +
                            "run_id VARCHAR(36)," +
                            "title_raw VARCHAR(500)," +
                            "title_standard VARCHAR(500)," +
                            "company VARCHAR(300)," +
                            "city VARCHAR(150)," +
                            "industry VARCHAR(200)," +
                            "salary_min DECIMAL(12,2),salary_max DECIMAL(12,2)," +
                            "education VARCHAR(150),experience_text VARCHAR(300),source_name VARCHAR(300)," +
                            "description_clean MEDIUMTEXT," +
                            "published_at DATE,published_year INT," +
                            "tech_stack VARCHAR(120),level_name VARCHAR(80)," +
                            "content_hash CHAR(64),template_hash CHAR(64)," +
                            "quality_score DECIMAL(10,6) DEFAULT 0,stale_score DECIMAL(10,6) DEFAULT 0," +
                            "duplicate_group VARCHAR(80),duplicate_weight DECIMAL(10,6) DEFAULT 1," +
                            "skill_count INT DEFAULT 0,valid_for_analysis TINYINT(1) DEFAULT 0," +
                            "governance_status VARCHAR(40),governed_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                            "INDEX idx_zhitu_gov_year(published_year)," +
                            "INDEX idx_zhitu_gov_date(published_at)," +
                            "INDEX idx_zhitu_gov_title(title_standard(120))," +
                            "INDEX idx_zhitu_gov_template(template_hash)," +
                            "INDEX idx_zhitu_gov_valid(valid_for_analysis,published_year)," +
                            "INDEX idx_zhitu_gov_company(company(100))" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
            if (!raw.columnExists(GOVERNED_TABLE, "is_deleted")) {
                raw.update("ALTER TABLE `" + GOVERNED_TABLE + "` ADD COLUMN is_deleted TINYINT(1) NOT NULL DEFAULT 0");
            }
            if (!raw.columnExists(GOVERNED_TABLE, "manual_modified")) {
                raw.update("ALTER TABLE `" + GOVERNED_TABLE + "` ADD COLUMN manual_modified TINYINT(1) NOT NULL DEFAULT 0");
            }
            if (!raw.columnExists(GOVERNED_TABLE, "manual_modified_at")) {
                raw.update("ALTER TABLE `" + GOVERNED_TABLE + "` ADD COLUMN manual_modified_at DATETIME NULL");
            }
            if (!raw.columnExists(GOVERNED_TABLE, "deleted_at")) {
                raw.update("ALTER TABLE `" + GOVERNED_TABLE + "` ADD COLUMN deleted_at DATETIME NULL");
            }
            raw.update(
                    "CREATE TABLE IF NOT EXISTS `" + SKILL_TABLE + "`(" +
                            "id BIGINT PRIMARY KEY AUTO_INCREMENT,raw_job_id BIGINT NOT NULL," +
                            "skill_name VARCHAR(200) NOT NULL,tech_stack VARCHAR(120),category VARCHAR(120)," +
                            "requirement_type VARCHAR(40),confidence DECIMAL(10,6),evidence_text VARCHAR(1000)," +
                            "created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                            "UNIQUE KEY uk_zhitu_gov_skill(raw_job_id,skill_name)," +
                            "INDEX idx_zhitu_gov_skill_name(skill_name)," +
                            "INDEX idx_zhitu_gov_skill_raw(raw_job_id)" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
            if (!raw.columnExists(SKILL_TABLE, "origin_type")) {
                raw.update("ALTER TABLE `" + SKILL_TABLE + "` ADD COLUMN origin_type VARCHAR(30) NOT NULL DEFAULT 'AUTO'");
            }
            raw.update(
                    "CREATE TABLE IF NOT EXISTS `" + ISSUE_TABLE + "`(" +
                            "id BIGINT PRIMARY KEY AUTO_INCREMENT,raw_job_id BIGINT NOT NULL,run_id VARCHAR(36)," +
                            "issue_type VARCHAR(80),field_name VARCHAR(100),severity VARCHAR(30),issue_message VARCHAR(1200)," +
                            "created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                            "UNIQUE KEY uk_zhitu_issue(raw_job_id,issue_type,field_name)," +
                            "INDEX idx_zhitu_issue_raw(raw_job_id),INDEX idx_zhitu_issue_type(issue_type,severity)" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
            raw.update(
                    "CREATE TABLE IF NOT EXISTS `" + GOVERNANCE_RUN_TABLE + "`(" +
                            "run_id VARCHAR(36) PRIMARY KEY,status VARCHAR(30),total_target BIGINT DEFAULT 0," +
                            "processed_count BIGINT DEFAULT 0,success_count BIGINT DEFAULT 0,failed_count BIGINT DEFAULT 0," +
                            "valid_count BIGINT DEFAULT 0,duplicate_count BIGINT DEFAULT 0,last_raw_id BIGINT DEFAULT 0," +
                            "current_stage VARCHAR(300),batch_size INT,error_message VARCHAR(2000)," +
                            "started_at DATETIME,finished_at DATETIME," +
                            "INDEX idx_zhitu_gov_run_time(started_at),INDEX idx_zhitu_gov_run_status(status)" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
            raw.update(
                    "CREATE TABLE IF NOT EXISTS `" + DUP_CLUSTER_TABLE + "`(" +
                            "template_hash CHAR(64) PRIMARY KEY,root_raw_job_id BIGINT,member_count BIGINT," +
                            "INDEX idx_zhitu_dup_root(root_raw_job_id)" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
        
            schemaReady.set(true);
        }
    }


    private void purgeHoldoutFromDerived() {
        int year = temporalDatasetService.defaultHoldoutYear();
        raw.update(
                "DELETE s FROM `" + SKILL_TABLE + "` s " +
                        "JOIN `" + TemporalDatasetService.HOLDOUT_TABLE + "` h ON h.raw_job_id=s.raw_job_id " +
                        "WHERE h.holdout_year=?",
                year
        );
        raw.update(
                "DELETE i FROM `" + ISSUE_TABLE + "` i " +
                        "JOIN `" + TemporalDatasetService.HOLDOUT_TABLE + "` h ON h.raw_job_id=i.raw_job_id " +
                        "WHERE h.holdout_year=?",
                year
        );
        raw.update(
                "DELETE g FROM `" + GOVERNED_TABLE + "` g " +
                        "JOIN `" + TemporalDatasetService.HOLDOUT_TABLE + "` h ON h.raw_job_id=g.raw_job_id " +
                        "WHERE h.holdout_year=?",
                year
        );
    }

    private void resetDerivedData() {
        raw.update("DELETE FROM `" + SKILL_TABLE + "`");
        raw.update("DELETE FROM `" + ISSUE_TABLE + "`");
        raw.update("DELETE FROM `" + GOVERNED_TABLE + "`");
        raw.update("DELETE FROM `" + GOVERNANCE_RUN_TABLE + "`");
        raw.update("DELETE FROM `" + DUP_CLUSTER_TABLE + "`");
    }

    private void updateRun(
            String runId,
            String status,
            long processed,
            long valid,
            long failed,
            long lastRawId,
            String stage
    ) {
        // V8：这里绝不再执行 COUNT(*) 全表统计；所有计数由当前批次在内存中增量维护。
        raw.update(
                "UPDATE `" + GOVERNANCE_RUN_TABLE + "` SET status=?,processed_count=?,success_count=?," +
                        "failed_count=?,valid_count=?,last_raw_id=?,current_stage=? WHERE run_id=?",
                status, processed, processed, failed, valid, lastRawId, stage, runId
        );
    }

    private void normalizeInterruptedRuns() {
        // 高频进度轮询不应该每次都执行 UPDATE。一个后端生命周期只需要归一化一次旧 RUNNING 任务。
        if (running.get()) return;
        if (!interruptedRunsNormalized.compareAndSet(false, true)) return;

        raw.update(
                "UPDATE `" + GOVERNANCE_RUN_TABLE + "` SET status='PAUSED'," +
                        "current_stage='后端曾停止，任务已转为可恢复状态' " +
                        "WHERE status='RUNNING'"
        );
    }

    private Map<String, Object> latestCheckpoint() {
        List<Map<String, Object>> rows = raw.list(
                "SELECT processed_count,valid_count,last_raw_id,status,started_at " +
                        "FROM `" + GOVERNANCE_RUN_TABLE + "` " +
                        "WHERE status IN ('RUNNING','PAUSED','FAILED','COMPLETED') " +
                        "ORDER BY last_raw_id DESC, started_at DESC LIMIT 1"
        );
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private Map<String, Object> latestRun() {
        List<Map<String, Object>> rows = raw.list(
                "SELECT * FROM `" + GOVERNANCE_RUN_TABLE + "` ORDER BY started_at DESC LIMIT 1"
        );
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private Map<String, Object> statusMessage(String status, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("message", message);
        result.put("running", running.get());
        return result;
    }

    private void assertSourceReady() {
        if (!raw.ping()) {
            throw new IllegalStateException(
                    "无法连接 career_data_governance MySQL。请检查 app.raw-database 的 URL、用户名和密码。"
            );
        }
        if (!raw.tableExists(raw.rawTable())) {
            throw new IllegalStateException("数据库中不存在原始岗位表：" + raw.rawTable());
        }
        schemaService.resolve();
    }

    private String cleanDescription(String text) {
        if (text == null) return "";
        String value = HTML.matcher(text).replaceAll(" ");
        value = CONTACT.matcher(value).replaceAll(" ");
        value = AD.matcher(value).replaceAll(" ");
        value = value.replace('\u00a0', ' ')
                .replaceAll("[\\t\\r]+", " ")
                .replaceAll("[ ]{2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return abbreviate(value, 60000);
    }

    private String normalizeTitle(String title) {
        if (title == null) return "";
        String value = TITLE_BRACKET.matcher(title).replaceAll(" ");
        value = TITLE_NOISE.matcher(value).replaceAll(" ");
        value = value.replaceAll("[|丨/\\\\]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (value.length() > 120) value = value.substring(0, 120).trim();
        return value;
    }

    private String templateFingerprint(String description) {
        if (description == null || description.isBlank()) return "EMPTY";
        String value = description.toLowerCase(Locale.ROOT)
                .replaceAll("\\d+(?:\\.\\d+)?", "#")
                .replaceAll("[a-f0-9]{16,}", "#")
                .replaceAll("(公司|集团|科技|有限|招聘|岗位职责|任职要求|职位要求|职位描述)", " ")
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff+#.]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return abbreviate(value, 2500);
    }

    private String inferStack(String text, List<MassSkillDictionary.SkillHit> hits) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (MassSkillDictionary.SkillHit hit : hits) {
            counts.merge(hit.stack(), 1, Integer::sum);
        }
        String best = null;
        int max = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > max) {
                best = entry.getKey();
                max = entry.getValue();
            }
        }
        if (best != null) return best;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.matches("(?s).*(大模型|智能体|agent|rag|prompt).*")) return "大模型应用";
        if (lower.matches("(?s).*(hadoop|spark|flink|数仓|数据治理).*")) return "大数据";
        if (lower.matches("(?s).*(java|spring|微服务|后端).*")) return "后端开发";
        if (lower.matches("(?s).*(物联网|mqtt|嵌入式|边缘计算).*")) return "物联网";
        if (lower.matches("(?s).*(视觉|机器人|数字孪生|智能系统).*")) return "智能系统";
        return "人工智能";
    }

    private String inferLevel(String text, String experience) {
        String value = (text + " " + Objects.requireNonNullElse(experience, "")).toLowerCase(Locale.ROOT);
        if (value.matches("(?s).*(专家|首席|架构|资深|高级|senior|lead|principal|8年以上|十年以上).*")) return "高级";
        if (value.matches("(?s).*(中级|3-5年|3年以上|三年以上|5年以上|五年以上).*")) return "中级";
        if (value.matches("(?s).*(实习|应届|校招|初级|1年以下|1-3年).*")) return "初级";
        return "未标注";
    }

    private double qualityScore(String title, String company, String description, Integer year, int skillCount) {
        double score = 0;
        if (!title.isBlank()) score += 0.22;
        if (!company.isBlank()) score += 0.10;
        if (description.length() >= 40) score += 0.18;
        if (description.length() >= 150) score += 0.10;
        if (description.matches("(?s).*(职责|负责|工作内容|任职|要求|技能).*")) score += 0.12;
        if (skillCount > 0) score += 0.14;
        if (skillCount >= 3) score += 0.06;
        if (year != null) score += 0.08;
        return round6(Math.min(1D, score));
    }

    private double staleScore(Integer year) {
        if (year == null) return 1D;
        int current = LocalDate.now().getYear();
        return round6(Math.max(0D, Math.min(1D, (current - year) / 8D)));
    }

    private Object[] issue(long rawId, String runId, String type, String field, String severity, String message) {
        return new Object[]{rawId, runId, type, field, severity, message};
    }

    private Number number(Object value) {
        if (value instanceof Number number) return number;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private Double decimal(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        try {
            String cleaned = String.valueOf(value).replaceAll("[^0-9.]", "");
            return cleaned.isBlank() ? null : Double.parseDouble(cleaned);
        } catch (Exception ignored) {
            return null;
        }
    }

    private LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof Date date) return date.toLocalDate();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime().toLocalDate();
        if (value instanceof LocalDate date) return date;
        try {
            return LocalDate.parse(String.valueOf(value).substring(0, 10));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean validYear(int year) {
        return year >= 2000 && year <= 2100;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String normalizeSimple(String value, int max) {
        if (value == null) return "";
        String result = value.replaceAll("\\s+", " ").trim();
        return abbreviate(result, max);
    }

    private String abbreviate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private double round6(double value) {
        return Math.round(value * 1_000_000D) / 1_000_000D;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private record BatchResult(long lastRawId, long failed, long valid) {}
}
