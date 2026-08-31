package com.zhitu.service;

import com.zhitu.repository.RawDatabaseClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 百万级原始岗位数据的训练/测试划分服务。
 *
 * 不再要求原表必须存在 title/company/published_at 英文字段，
 * 而是通过 RawJobSchemaService 自动识别“招聘岗位/企业名称/招聘发布年份”等真实字段。
 */
@Service
public class TemporalDatasetService {

    public static final String HOLDOUT_TABLE = "zhitu_temporal_holdout";
    public static final String RUN_TABLE = "zhitu_forecast_run";
    public static final String CANDIDATE_TABLE = "zhitu_forecast_candidate";

    private final RawDatabaseClient raw;
    private final RawJobSchemaService schemaService;
    private final int defaultHoldoutYear;
    private final int defaultHoldoutSize;
    private final String defaultSeed;
    private final int analysisMinGovernedRows;

    public TemporalDatasetService(
            RawDatabaseClient raw,
            RawJobSchemaService schemaService,
            @Value("${app.raw-database.holdout-year:2026}") int defaultHoldoutYear,
            @Value("${app.raw-database.holdout-size:1000}") int defaultHoldoutSize,
            @Value("${app.raw-database.holdout-seed:zhitu-2026-v1}") String defaultSeed,
            @Value("${app.raw-database.analysis-min-governed-rows:100}") int analysisMinGovernedRows
    ) {
        this.raw = raw;
        this.schemaService = schemaService;
        this.defaultHoldoutYear = defaultHoldoutYear;
        this.defaultHoldoutSize = defaultHoldoutSize;
        this.defaultSeed = defaultSeed;
        this.analysisMinGovernedRows = Math.max(100, analysisMinGovernedRows);
    }

    public Map<String, Object> overview() {
        assertRawReady();
        ensureExperimentSchema();

        String table = raw.quotedRawTable();
        String yearExpr = schemaService.publishedYearExpr("r");
        long total = raw.scalarLong("SELECT COUNT(*) FROM " + table);
        long dated = raw.scalarLong(
                "SELECT COUNT(*) FROM " + table + " r WHERE " + yearExpr + " BETWEEN 2000 AND 2100"
        );
        long undated = total - dated;
        long holdout = raw.scalarLong(
                "SELECT COUNT(*) FROM `" + HOLDOUT_TABLE + "` WHERE holdout_year = ?",
                defaultHoldoutYear
        );
        long trainPool = Math.max(0, total - holdout);
        long holdoutYearTotal = raw.scalarLong(
                "SELECT COUNT(*) FROM " + table + " r WHERE " + yearExpr + " = ?",
                defaultHoldoutYear
        );
        long governedRows = raw.tableExists(RawJobGovernanceService.GOVERNED_TABLE)
                ? raw.scalarLong("SELECT COUNT(*) FROM `" + RawJobGovernanceService.GOVERNED_TABLE + "`")
                : 0L;
        long validGovernedRows = raw.tableExists(RawJobGovernanceService.GOVERNED_TABLE)
                ? raw.scalarLong("SELECT COUNT(*) FROM `" + RawJobGovernanceService.GOVERNED_TABLE + "` WHERE valid_for_analysis=1")
                : 0L;

        RawJobSchemaService.SchemaMapping mapping = schemaService.resolve();
        boolean yearIndexed = mapping.publishedYear() != null &&
                raw.indexOnColumnExists(raw.rawTable(), mapping.publishedYear());
        boolean dateIndexed = mapping.publishedAt() != null &&
                raw.indexOnColumnExists(raw.rawTable(), mapping.publishedAt());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connected", true);
        result.put("database", "career_data_governance");
        result.put("rawTable", raw.rawTable());
        result.put("totalRows", total);
        result.put("datedRows", dated);
        result.put("undatedRows", undated);
        result.put("trainPoolRows", trainPool);
        boolean snapshotReady = governedRows >= analysisMinGovernedRows && validGovernedRows > 0;
        boolean fullGovernanceComplete = holdout == defaultHoldoutSize && governedRows >= trainPool;
        long snapshotVersion = governedRows < analysisMinGovernedRows
                ? 0L
                : (governedRows / analysisMinGovernedRows) * (long) analysisMinGovernedRows;

        result.put("governedRows", governedRows);
        result.put("validGovernedRows", validGovernedRows);
        // 达到 100 条治理记录即可运行阶段性年度回测；全量完成状态单独返回。
        result.put("analysisReady", snapshotReady);
        result.put("snapshotReady", snapshotReady);
        result.put("fullGovernanceComplete", fullGovernanceComplete);
        result.put("analysisMinGovernedRows", analysisMinGovernedRows);
        result.put("snapshotVersion", snapshotVersion);
        result.put("analysisScope", fullGovernanceComplete ? "FULL_GOVERNANCE" : "PARTIAL_SNAPSHOT");
        result.put("holdoutYear", defaultHoldoutYear);
        result.put("holdoutTarget", defaultHoldoutSize);
        result.put("holdoutRows", holdout);
        result.put("holdoutYearRows", holdoutYearTotal);
        result.put("holdoutReady", holdout == defaultHoldoutSize);
        result.put("publishedAtIndexed", yearIndexed || dateIndexed);
        result.put("yearColumn", mapping.publishedYear());
        result.put("dateColumn", mapping.publishedAt());
        result.put("schema", schemaService.describe());
        result.put("yearStats", yearStats());
        return result;
    }

    public Map<String, Object> prepareDefaultHoldout(boolean reset) {
        return prepareHoldout(defaultHoldoutYear, defaultHoldoutSize, defaultSeed, reset);
    }

    public Map<String, Object> prepareHoldout(int year, int size, String seed, boolean reset) {
        assertRawReady();
        ensureExperimentSchema();

        if (year < 2000 || year > 2100) {
            throw new IllegalArgumentException("测试年份不合法：" + year);
        }
        if (size < 100 || size > 100_000) {
            throw new IllegalArgumentException("测试集大小需在 100~100000 之间");
        }

        String actualSeed = seed == null || seed.isBlank() ? defaultSeed : seed.trim();
        String table = raw.quotedRawTable();
        String yearExpr = schemaService.publishedYearExpr("r");
        String idExpr = schemaService.idExpr("r");

        long available = raw.scalarLong(
                "SELECT COUNT(*) FROM " + table + " r WHERE " + yearExpr + " = ?",
                year
        );
        if (available < size) {
            throw new IllegalArgumentException(
                    year + " 年只有 " + available + " 条可识别年份的岗位数据，无法抽取 " + size + " 条测试样本"
            );
        }

        long existing = raw.scalarLong(
                "SELECT COUNT(*) FROM `" + HOLDOUT_TABLE + "` WHERE holdout_year = ?",
                year
        );
        if (reset || existing != size) {
            raw.update("DELETE FROM `" + HOLDOUT_TABLE + "` WHERE holdout_year = ?", year);
            raw.update(
                    "INSERT INTO `" + HOLDOUT_TABLE + "`(raw_job_id, holdout_year, sample_seed) " +
                            "SELECT " + idExpr + ", ?, ? FROM " + table + " r " +
                            "WHERE " + yearExpr + " = ? " +
                            "ORDER BY CRC32(CONCAT(CAST(" + idExpr + " AS CHAR), ':', ?)), " + idExpr + " " +
                            "LIMIT " + size,
                    year,
                    actualSeed,
                    year,
                    actualSeed
            );
        }

        long holdout = raw.scalarLong(
                "SELECT COUNT(*) FROM `" + HOLDOUT_TABLE + "` WHERE holdout_year = ?",
                year
        );
        long total = raw.scalarLong("SELECT COUNT(*) FROM " + table);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year", year);
        result.put("requested", size);
        result.put("holdoutRows", holdout);
        result.put("trainPoolRows", Math.max(0, total - holdout));
        result.put("seed", actualSeed);
        result.put("strategy", "DETERMINISTIC_HASH_HOLDOUT");
        result.put("yearField", schemaService.resolve().publishedYear());
        result.put("dateField", schemaService.resolve().publishedAt());
        result.put("leakageGuard", "测试样本只记录 raw_job_id；全量治理和训练阶段均排除该 ID 集合");
        return result;
    }

    public List<Map<String, Object>> yearStats() {
        assertRawReady();
        ensureExperimentSchema();
        String table = raw.quotedRawTable();
        String yearExpr = schemaService.publishedYearExpr("r");
        String idExpr = schemaService.idExpr("r");

        List<Map<String, Object>> rawRows = raw.list(
                "SELECT " + yearExpr + " AS data_year, COUNT(*) AS total_rows, " +
                        "SUM(CASE WHEN h.raw_job_id IS NOT NULL THEN 1 ELSE 0 END) AS test_rows " +
                        "FROM " + table + " r " +
                        "LEFT JOIN `" + HOLDOUT_TABLE + "` h ON h.raw_job_id = " + idExpr + " " +
                        "WHERE " + yearExpr + " BETWEEN 2000 AND 2100 " +
                        "GROUP BY " + yearExpr + " ORDER BY data_year"
        );

        Map<Integer, Long> governedByYear = new LinkedHashMap<>();
        Map<Integer, Long> validByYear = new LinkedHashMap<>();
        if (raw.tableExists(RawJobGovernanceService.GOVERNED_TABLE)) {
            for (Map<String, Object> row : raw.list(
                    "SELECT published_year AS data_year,COUNT(*) AS governed_rows," +
                            "SUM(CASE WHEN valid_for_analysis=1 THEN 1 ELSE 0 END) AS valid_rows " +
                            "FROM `" + RawJobGovernanceService.GOVERNED_TABLE + "` " +
                            "WHERE published_year BETWEEN 2000 AND 2100 GROUP BY published_year ORDER BY published_year"
            )) {
                int year = number(row.get("data_year")).intValue();
                governedByYear.put(year, number(row.get("governed_rows")).longValue());
                validByYear.put(year, number(row.get("valid_rows")).longValue());
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rawRows) {
            int year = number(row.get("data_year")).intValue();
            long total = number(row.get("total_rows")).longValue();
            long test = number(row.get("test_rows")).longValue();
            long rawTrain = total - test;
            long governed = governedByYear.getOrDefault(year, 0L);
            long valid = validByYear.getOrDefault(year, 0L);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("year", year);
            item.put("totalRows", total);
            item.put("rawTrainRows", rawTrain);
            item.put("governedRows", governed);
            item.put("validRows", valid);
            // 年度回测真正读取的是治理后且 valid_for_analysis=1 的记录，因此 trainRows 必须反映真实训练样本。
            item.put("trainRows", valid);
            item.put("testRows", test);
            item.put("role", test > 0 ? "GOVERNED_TRAIN + HOLDOUT" : "GOVERNED_TRAIN");
            result.add(item);
        }
        return result;
    }

    public List<Map<String, Object>> holdoutSample(int limit) {
        assertRawReady();
        ensureExperimentSchema();
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String table = raw.quotedRawTable();
        String idExpr = schemaService.idExpr("r");
        return raw.list(
                "SELECT " + idExpr + " AS id," +
                        schemaService.titleExpr("r") + " AS title," +
                        schemaService.companyExpr("r") + " AS company," +
                        schemaService.industryExpr("r") + " AS industry," +
                        schemaService.publishedDateExpr("r") + " AS published_at," +
                        schemaService.publishedYearExpr("r") + " AS published_year," +
                        "h.holdout_year,h.sample_seed " +
                        "FROM `" + HOLDOUT_TABLE + "` h JOIN " + table + " r ON " + idExpr + " = h.raw_job_id " +
                        "WHERE h.holdout_year = ? ORDER BY h.id LIMIT " + safeLimit,
                defaultHoldoutYear
        );
    }

    public int defaultHoldoutYear() {
        return defaultHoldoutYear;
    }

    public int defaultHoldoutSize() {
        return defaultHoldoutSize;
    }

    public void assertRawReady() {
        if (!raw.ping()) {
            throw new IllegalStateException(
                    "无法连接历史岗位 MySQL。请确认 MySQL 已启动，并检查 application.yml 中 app.raw-database 的 URL、用户名和密码。"
            );
        }
        if (!raw.tableExists(raw.rawTable())) {
            throw new IllegalStateException("数据库中不存在原始岗位表：" + raw.rawTable());
        }
        schemaService.resolve();
    }

    public void ensureExperimentSchema() {
        raw.update(
                "CREATE TABLE IF NOT EXISTS `" + HOLDOUT_TABLE + "` (" +
                        "id BIGINT PRIMARY KEY AUTO_INCREMENT," +
                        "raw_job_id BIGINT NOT NULL," +
                        "holdout_year INT NOT NULL," +
                        "sample_seed VARCHAR(100) NOT NULL," +
                        "created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                        "UNIQUE KEY uk_zhitu_holdout_raw(raw_job_id)," +
                        "INDEX idx_zhitu_holdout_year(holdout_year)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        );

        raw.update(
                "CREATE TABLE IF NOT EXISTS `" + RUN_TABLE + "` (" +
                        "run_id VARCHAR(36) PRIMARY KEY," +
                        "batch_id VARCHAR(36) NOT NULL," +
                        "train_year INT NOT NULL," +
                        "test_year INT NOT NULL," +
                        "train_rows BIGINT DEFAULT 0," +
                        "test_rows BIGINT DEFAULT 0," +
                        "prediction_count INT DEFAULT 0," +
                        "actual_emerging_count INT DEFAULT 0," +
                        "matched_count INT DEFAULT 0," +
                        "precision_score DECIMAL(10,6) DEFAULT 0," +
                        "recall_score DECIMAL(10,6) DEFAULT 0," +
                        "f1_score DECIMAL(10,6) DEFAULT 0," +
                        "avg_similarity DECIMAL(10,6) DEFAULT 0," +
                        "calibration_score DECIMAL(10,6) DEFAULT 0," +
                        "trust_score DECIMAL(10,6) DEFAULT 0," +
                        "top_k INT DEFAULT 30," +
                        "test_scope VARCHAR(40)," +
                        "config_json JSON," +
                        "created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                        "INDEX idx_zhitu_forecast_batch(batch_id)," +
                        "INDEX idx_zhitu_forecast_year(train_year, test_year)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        );

        raw.update(
                "CREATE TABLE IF NOT EXISTS `" + CANDIDATE_TABLE + "` (" +
                        "id BIGINT PRIMARY KEY AUTO_INCREMENT," +
                        "run_id VARCHAR(36) NOT NULL," +
                        "rank_no INT NOT NULL," +
                        "predicted_title VARCHAR(500) NOT NULL," +
                        "normalized_title VARCHAR(500)," +
                        "train_count BIGINT DEFAULT 0," +
                        "previous_count BIGINT DEFAULT 0," +
                        "company_count BIGINT DEFAULT 0," +
                        "h1_count BIGINT DEFAULT 0," +
                        "h2_count BIGINT DEFAULT 0," +
                        "novelty_score DECIMAL(10,6) DEFAULT 0," +
                        "momentum_score DECIMAL(10,6) DEFAULT 0," +
                        "forecast_score DECIMAL(10,6) DEFAULT 0," +
                        "confidence DECIMAL(10,6) DEFAULT 0," +
                        "actual_title VARCHAR(500)," +
                        "actual_count DECIMAL(18,4) DEFAULT 0," +
                        "similarity DECIMAL(10,6) DEFAULT 0," +
                        "hit_flag TINYINT(1) DEFAULT 0," +
                        "evidence_json JSON," +
                        "created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                        "INDEX idx_zhitu_candidate_run(run_id)," +
                        "INDEX idx_zhitu_candidate_hit(run_id, hit_flag)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        );
    }

    private Number number(Object value) {
        return value instanceof Number n ? n : 0;
    }
}
