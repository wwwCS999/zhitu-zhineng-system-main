package com.zhitu.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhitu.repository.RawDatabaseClient;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 可解释的“t 年岗位 -> t+1 年新岗位/萌芽岗位”年度回测器。
 *
 * 这不是用未来数据训练模型。每个窗口严格只从 trainYear 的岗位分布计算预测特征，
 * 然后再用 testYear 数据评价命中情况。最终 2025->2026 只读取固定的 1000 条 2026 holdout。
 */
@Service
public class TemporalForecastService {

    private static final Pattern LEVEL_WORDS = Pattern.compile(
            "(?i)(初级|中级|高级|资深|专家|首席|助理|实习|校招|社招|急招|诚聘|junior|senior|sr\\.?|jr\\.?|lead|principal|staff)"
    );
    private static final Pattern BRACKETS = Pattern.compile("[（(【\\[].{0,30}?[）)】\\]]");
    private static final Pattern JOB_SUFFIX = Pattern.compile(
            "(?i)(开发工程师|研发工程师|算法工程师|软件工程师|工程师|开发岗|研发岗|专员|顾问|岗位|developer|engineer|specialist|consultant)$"
    );
    private static final Pattern NOISE = Pattern.compile("(?i)(招聘|直招|高薪|双休|五险一金|\\d+k[-~—]?\\d*k?)");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9\\u4e00-\\u9fff+#.]+");

    private final RawDatabaseClient raw;
    private final TemporalDatasetService datasetService;
    private final RawJobSchemaService schemaService;
    private final RawJobGovernanceService governanceService;
    private final ObjectMapper objectMapper;

    public TemporalForecastService(
            RawDatabaseClient raw,
            TemporalDatasetService datasetService,
            RawJobSchemaService schemaService,
            RawJobGovernanceService governanceService,
            ObjectMapper objectMapper
    ) {
        this.raw = raw;
        this.datasetService = datasetService;
        this.schemaService = schemaService;
        this.governanceService = governanceService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> backtest(int startYear, int endYear, int topK, int minSupport) {
        datasetService.assertRawReady();
        datasetService.ensureExperimentSchema();
        // V10：只要求当前治理快照至少达到 100 条，不再等待百万数据全部治理完成。
        governanceService.assertReadyForAnalysis();
        Map<String, Object> snapshot = governanceService.analysisSnapshot();
        boolean fullGovernanceComplete = Boolean.TRUE.equals(snapshot.get("fullGovernanceComplete"));

        if (startYear < 2000 || startYear >= endYear) {
            throw new IllegalArgumentException("startYear 必须早于 endYear");
        }
        if (endYear > 2100 || endYear - startYear > 20) {
            throw new IllegalArgumentException("年度回测跨度过大");
        }
        int safeTopK = Math.max(5, Math.min(topK, 200));
        int safeMinSupport = Math.max(2, Math.min(minSupport, 100));

        // 只要回测覆盖 2026，就先锁定 2026 的 1000 条测试集。
        if (endYear >= datasetService.defaultHoldoutYear()) {
            datasetService.prepareDefaultHoldout(false);
        }

        String batchId = UUID.randomUUID().toString();
        Map<Integer, Map<String, TitleStats>> yearData = loadTrainingAggregates(startYear - 1, endYear);
        List<Map<String, Object>> runs = new ArrayList<>();
        List<Map<String, Object>> skippedWindows = new ArrayList<>();
        Map<Integer, Long> yearRowCounts = new HashMap<>();
        Map<Integer, Long> yearTestCounts = new HashMap<>();
        for (Map<String, Object> row : datasetService.yearStats()) {
            int year = number(row.get("year")).intValue();
            yearRowCounts.put(year, number(row.get("trainRows")).longValue());
            yearTestCounts.put(year, number(row.get("testRows")).longValue());
        }

        for (int trainYear = startYear; trainYear < endYear; trainYear++) {
            int testYear = trainYear + 1;
            Map<String, TitleStats> previous = yearData.getOrDefault(trainYear - 1, Map.of());
            Map<String, TitleStats> train = yearData.getOrDefault(trainYear, Map.of());
            Map<String, TitleStats> actual = testYear == datasetService.defaultHoldoutYear()
                    ? loadHoldoutAggregates(testYear)
                    : yearData.getOrDefault(testYear, Map.of());

            // 当前治理快照可能只覆盖了部分年份。缺少训练年或验证年数据时，
            // 不再让整个回测失败，而是跳过该窗口并明确返回原因。
            if (train.isEmpty() || actual.isEmpty()) {
                Map<String, Object> skipped = new LinkedHashMap<>();
                skipped.put("trainYear", trainYear);
                skipped.put("testYear", testYear);
                skipped.put("trainAvailable", !train.isEmpty());
                skipped.put("testAvailable", !actual.isEmpty());
                skipped.put("reason", train.isEmpty()
                        ? "当前解析快照中尚无足够的 " + trainYear + " 年治理 JD"
                        : "当前解析快照中尚无足够的 " + testYear + " 年验证数据");
                skippedWindows.add(skipped);
                continue;
            }

            Map<String, Object> run = evaluateWindow(
                    batchId,
                    trainYear,
                    testYear,
                    previous,
                    train,
                    actual,
                    safeTopK,
                    safeMinSupport,
                    testYear == datasetService.defaultHoldoutYear(),
                    fullGovernanceComplete,
                    yearRowCounts.getOrDefault(trainYear, 0L),
                    testYear == datasetService.defaultHoldoutYear()
                            ? yearTestCounts.getOrDefault(testYear, 0L)
                            : yearRowCounts.getOrDefault(testYear, 0L)
            );
            runs.add(run);
        }

        double meanPrecision = average(runs, "precision");
        double meanRecall = average(runs, "recall");
        double meanF1 = average(runs, "f1");
        double meanTrust = average(runs, "trustScore");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);
        result.put("startYear", startYear);
        result.put("endYear", endYear);
        result.put("topK", safeTopK);
        result.put("minSupport", safeMinSupport);
        result.put("runs", runs);
        result.put("skippedWindows", skippedWindows);
        result.put("snapshot", snapshot);
        result.put("snapshotVersion", snapshot.getOrDefault("snapshotVersion", 0));
        result.put("analysisScope", fullGovernanceComplete ? "FULL_GOVERNANCE" : "PARTIAL_SNAPSHOT");
        result.put("meanPrecision", round6(meanPrecision));
        result.put("meanRecall", round6(meanRecall));
        result.put("meanF1", round6(meanF1));
        result.put("meanTrustScore", round6(meanTrust));
        result.put("method", "YEAR_T_TO_YEAR_T_PLUS_1_EXPLAINABLE_EMERGENCE_FORECAST");
        result.put("note", fullGovernanceComplete
                ? "当前使用全量治理数据。2026 年只用固定 1000 条 holdout 做最终验证；历史年份采用逐年滚动回测，不使用未来年份信息。"
                : "当前使用阶段性解析快照。每累计至少 100 条治理记录即可重新运行；尚未出现在当前快照中的年度窗口会自动跳过。全量治理完成后请再运行一次作为最终比赛结果。");
        return result;
    }

    public List<Map<String, Object>> runs(int limit) {
        datasetService.assertRawReady();
        datasetService.ensureExperimentSchema();
        int safe = Math.max(1, Math.min(limit, 200));
        return raw.list(
                "SELECT run_id, batch_id, train_year, test_year, train_rows, test_rows, " +
                        "prediction_count, actual_emerging_count, matched_count, precision_score, recall_score, " +
                        "f1_score, avg_similarity, calibration_score, trust_score, top_k, test_scope, created_at " +
                        "FROM `" + TemporalDatasetService.RUN_TABLE + "` ORDER BY created_at DESC, train_year DESC LIMIT " + safe
        );
    }

    public Map<String, Object> runDetail(String runId) {
        datasetService.assertRawReady();
        datasetService.ensureExperimentSchema();
        List<Map<String, Object>> runRows = raw.list(
                "SELECT * FROM `" + TemporalDatasetService.RUN_TABLE + "` WHERE run_id = ? LIMIT 1",
                runId
        );
        if (runRows.isEmpty()) {
            throw new IllegalArgumentException("预测运行不存在：" + runId);
        }
        List<Map<String, Object>> candidates = raw.list(
                "SELECT rank_no, predicted_title, normalized_title, train_count, previous_count, company_count, " +
                        "h1_count, h2_count, novelty_score, momentum_score, forecast_score, confidence, " +
                        "actual_title, actual_count, similarity, hit_flag, evidence_json " +
                        "FROM `" + TemporalDatasetService.CANDIDATE_TABLE + "` WHERE run_id = ? ORDER BY rank_no",
                runId
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", runRows.get(0));
        result.put("candidates", candidates);
        return result;
    }

    private Map<String, Object> evaluateWindow(
            String batchId,
            int trainYear,
            int testYear,
            Map<String, TitleStats> previous,
            Map<String, TitleStats> train,
            Map<String, TitleStats> actual,
            int topK,
            int minSupport,
            boolean holdoutTest,
            boolean fullGovernanceComplete,
            long trainRowsExact,
            long testRowsExact
    ) {
        List<ForecastCandidate> scored = new ArrayList<>();
        for (TitleStats current : train.values()) {
            if (current.count < minSupport || current.normalized.length() < 2) {
                continue;
            }
            TitleStats prev = previous.get(current.normalized);
            long previousCount = prev == null ? 0 : prev.count;

            double novelty = previousCount == 0
                    ? 1D
                    : clamp01(1D - Math.min(1D, previousCount / (double) Math.max(1, current.count)));
            double momentum = momentum(current.h1, current.h2);
            double support = clamp01(Math.log1p(current.count) / Math.log(60D));
            double diversity = clamp01(current.companyCount / (double) Math.max(3, Math.min(current.count, 15)));
            double forecastScore = clamp01(
                    0.34 * novelty +
                            0.28 * momentum +
                            0.20 * support +
                            0.18 * diversity
            );
            double confidence = clamp01(
                    0.38 * support +
                            0.34 * diversity +
                            0.18 * dataBalance(current.h1, current.h2) +
                            0.10 * (current.count >= 5 ? 1 : 0.6)
            );

            // 过于稳定、缺乏新颖度的传统岗位不作为“新岗位/萌芽岗位”预测候选。
            // 收紧判定：提高综合分门槛，并要求岗位相对前一年显著新增或年内明显加速，抑制稳定岗位的假阳性。
            if (forecastScore < 0.52) {
                continue;
            }
            boolean emergingSignal = novelty >= 0.40 || (novelty >= 0.15 && momentum >= 0.60);
            if (!emergingSignal) {
                continue;
            }
            scored.add(new ForecastCandidate(current, previousCount, novelty, momentum, forecastScore, confidence));
        }

        scored.sort(
                Comparator.comparingDouble(ForecastCandidate::forecastScore).reversed()
                        .thenComparing(Comparator.comparingLong((ForecastCandidate c) -> c.stats.count).reversed())
        );
        if (scored.size() > topK) {
            scored = new ArrayList<>(scored.subList(0, topK));
        }

        double testExpansionFactor = 1D;
        long testRows = testRowsExact;
        if (holdoutTest) {
            long fullYear = raw.scalarLong(
                    "SELECT COUNT(*) FROM " + raw.quotedRawTable() + " r WHERE " +
                            schemaService.publishedYearExpr("r") + " = ?",
                    testYear
            );
            long holdoutRows = raw.scalarLong(
                    "SELECT COUNT(*) FROM `" + TemporalDatasetService.HOLDOUT_TABLE + "` WHERE holdout_year = ?",
                    testYear
            );
            testRows = holdoutRows;
            if (holdoutRows > 0) {
                testExpansionFactor = fullYear / (double) holdoutRows;
            }
        }

        Set<String> actualEmerging = deriveActualEmerging(train, actual, holdoutTest, testExpansionFactor, minSupport);
        Set<String> matchedActual = new LinkedHashSet<>();
        int hits = 0;
        double similaritySum = 0D;
        double brierSum = 0D;

        String runId = UUID.randomUUID().toString();
        int rank = 1;
        for (ForecastCandidate candidate : scored) {
            Match match = bestMatch(candidate.stats.normalized, actual.values(), holdoutTest, testExpansionFactor);
            boolean growthConfirmed = false;
            if (match.stats != null && match.similarity >= 0.72) {
                double estimatedActualCount = match.stats.count * testExpansionFactor;
                growthConfirmed = isEmergingNextYear(candidate.stats.count, estimatedActualCount, holdoutTest, minSupport);
            }
            boolean hit = match.stats != null && match.similarity >= 0.72 && growthConfirmed;
            if (hit) {
                hits++;
                similaritySum += match.similarity;
                matchedActual.add(match.stats.normalized);
            }
            brierSum += Math.pow(candidate.forecastScore - (hit ? 1D : 0D), 2);

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("trainYear", trainYear);
            evidence.put("testYear", testYear);
            evidence.put("trainSamples", candidate.stats.count);
            evidence.put("previousYearSamples", candidate.previousCount);
            evidence.put("companyCount", candidate.stats.companyCount);
            evidence.put("firstHalf", candidate.stats.h1);
            evidence.put("secondHalf", candidate.stats.h2);
            evidence.put("holdoutEvaluation", holdoutTest);
            evidence.put("actualEstimatedFromHoldout", holdoutTest);

            raw.update(
                    "INSERT INTO `" + TemporalDatasetService.CANDIDATE_TABLE + "`(" +
                            "run_id, rank_no, predicted_title, normalized_title, train_count, previous_count, company_count, " +
                            "h1_count, h2_count, novelty_score, momentum_score, forecast_score, confidence, " +
                            "actual_title, actual_count, similarity, hit_flag, evidence_json" +
                            ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    runId,
                    rank++,
                    candidate.stats.displayTitle,
                    candidate.stats.normalized,
                    candidate.stats.count,
                    candidate.previousCount,
                    candidate.stats.companyCount,
                    candidate.stats.h1,
                    candidate.stats.h2,
                    round6(candidate.novelty),
                    round6(candidate.momentum),
                    round6(candidate.forecastScore),
                    round6(candidate.confidence),
                    match.stats == null ? null : match.stats.displayTitle,
                    match.stats == null ? 0D : round4(match.stats.count * testExpansionFactor),
                    round6(match.similarity),
                    hit ? 1 : 0,
                    toJson(evidence)
            );
        }

        int predictionCount = scored.size();
        double precision = predictionCount == 0 ? 0D : hits / (double) predictionCount;
        double recall = actualEmerging.isEmpty() ? 0D : matchedActual.stream().filter(actualEmerging::contains).count() / (double) actualEmerging.size();
        double f1 = precision + recall == 0 ? 0D : 2D * precision * recall / (precision + recall);
        double avgSimilarity = hits == 0 ? 0D : similaritySum / hits;
        double calibration = predictionCount == 0 ? 0D : clamp01(1D - brierSum / predictionCount);
        double avgConfidence = scored.stream().mapToDouble(ForecastCandidate::confidence).average().orElse(0D);
        double trustScore = clamp01(
                0.42 * precision +
                        0.20 * calibration +
                        0.18 * avgSimilarity +
                        0.20 * avgConfidence
        );
        long trainRows = trainRowsExact;

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("topK", topK);
        config.put("minSupport", minSupport);
        config.put("matchThreshold", 0.72);
        config.put("forecastThreshold", 0.46);
        config.put("holdout", holdoutTest);

        String testScope = holdoutTest
                ? "HOLDOUT_1000"
                : (fullGovernanceComplete ? "FULL_YEAR" : "GOVERNED_SNAPSHOT");

        raw.update(
                "INSERT INTO `" + TemporalDatasetService.RUN_TABLE + "`(" +
                        "run_id,batch_id,train_year,test_year,train_rows,test_rows,prediction_count,actual_emerging_count," +
                        "matched_count,precision_score,recall_score,f1_score,avg_similarity,calibration_score,trust_score,top_k,test_scope,config_json" +
                        ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                runId,
                batchId,
                trainYear,
                testYear,
                trainRows,
                testRows,
                predictionCount,
                actualEmerging.size(),
                hits,
                round6(precision),
                round6(recall),
                round6(f1),
                round6(avgSimilarity),
                round6(calibration),
                round6(trustScore),
                topK,
                testScope,
                toJson(config)
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("trainYear", trainYear);
        result.put("testYear", testYear);
        result.put("trainRows", trainRows);
        result.put("testRows", testRows);
        result.put("predictionCount", predictionCount);
        result.put("actualEmergingCount", actualEmerging.size());
        result.put("matchedCount", hits);
        result.put("precision", round6(precision));
        result.put("recall", round6(recall));
        result.put("f1", round6(f1));
        result.put("avgSimilarity", round6(avgSimilarity));
        result.put("calibration", round6(calibration));
        result.put("trustScore", round6(trustScore));
        result.put("testScope", testScope);
        return result;
    }

    private Map<Integer, Map<String, TitleStats>> loadTrainingAggregates(int startYear, int endYear) {
        String table = "`" + RawJobGovernanceService.GOVERNED_TABLE + "`";
        List<Map<String, Object>> rows = raw.list(
                "SELECT g.published_year AS data_year, g.title_standard AS raw_title, " +
                        "COUNT(DISTINCT COALESCE(g.duplicate_group, CONCAT('U-',g.raw_job_id))) AS sample_count, " +
                        "COUNT(DISTINCT NULLIF(TRIM(g.company),'')) AS company_count, " +
                        "COUNT(DISTINCT CASE WHEN MONTH(g.published_at) <= 6 " +
                        "THEN COALESCE(g.duplicate_group, CONCAT('U-',g.raw_job_id)) END) AS h1_count, " +
                        "COUNT(DISTINCT CASE WHEN MONTH(g.published_at) > 6 " +
                        "THEN COALESCE(g.duplicate_group, CONCAT('U-',g.raw_job_id)) END) AS h2_count " +
                        "FROM " + table + " g " +
                        "WHERE g.published_year BETWEEN ? AND ? " +
                        "AND g.valid_for_analysis = 1 " +
                        "AND g.title_standard IS NOT NULL AND TRIM(g.title_standard) <> '' " +
                        "GROUP BY g.published_year, g.title_standard",
                startYear,
                endYear
        );

        Map<Integer, Map<String, TitleStats>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            int year = number(row.get("data_year")).intValue();
            String rawTitle = String.valueOf(row.get("raw_title"));
            String normalized = normalizeTitle(rawTitle);
            if (normalized.length() < 2) {
                continue;
            }
            TitleStats next = new TitleStats(
                    normalized,
                    rawTitle,
                    number(row.get("sample_count")).longValue(),
                    number(row.get("company_count")).longValue(),
                    number(row.get("h1_count")).longValue(),
                    number(row.get("h2_count")).longValue()
            );
            result.computeIfAbsent(year, ignored -> new LinkedHashMap<>())
                    .merge(normalized, next, TitleStats::merge);
        }
        return result;
    }

    private Map<String, TitleStats> loadHoldoutAggregates(int year) {
        String rawTable = raw.quotedRawTable();
        String idExpr = schemaService.idExpr("r");
        String titleExpr = schemaService.titleExpr("r");
        String companyExpr = schemaService.companyExpr("r");
        String dateExpr = schemaService.publishedDateExpr("r");

        List<Map<String, Object>> rows = raw.list(
                "SELECT " + titleExpr + " AS raw_title, COUNT(*) AS sample_count, " +
                        "COUNT(DISTINCT NULLIF(TRIM(" + companyExpr + "),'')) AS company_count, " +
                        "SUM(CASE WHEN MONTH(" + dateExpr + ") <= 6 THEN 1 ELSE 0 END) AS h1_count, " +
                        "SUM(CASE WHEN MONTH(" + dateExpr + ") > 6 THEN 1 ELSE 0 END) AS h2_count " +
                        "FROM `" + TemporalDatasetService.HOLDOUT_TABLE + "` h " +
                        "JOIN " + rawTable + " r ON " + idExpr + " = h.raw_job_id " +
                        "WHERE h.holdout_year = ? AND " + titleExpr + " <> '' " +
                        "GROUP BY " + titleExpr,
                year
        );

        Map<String, TitleStats> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String rawTitle = String.valueOf(row.get("raw_title"));
            String normalized = normalizeTitle(rawTitle);
            if (normalized.length() < 2) {
                continue;
            }
            TitleStats next = new TitleStats(
                    normalized,
                    rawTitle,
                    number(row.get("sample_count")).longValue(),
                    number(row.get("company_count")).longValue(),
                    number(row.get("h1_count")).longValue(),
                    number(row.get("h2_count")).longValue()
            );
            result.merge(normalized, next, TitleStats::merge);
        }
        return result;
    }

    private Set<String> deriveActualEmerging(
            Map<String, TitleStats> train,
            Map<String, TitleStats> actual,
            boolean holdout,
            double expansionFactor,
            int minSupport
    ) {
        Set<String> result = new LinkedHashSet<>();
        for (TitleStats next : actual.values()) {
            TitleStats current = train.get(next.normalized);
            double currentCount = current == null ? 0D : current.count;
            double nextEstimated = next.count * expansionFactor;
            if (holdout) {
                // 1000 条抽样测试集以“抽到 + 估算后具备增长”作为真实萌芽信号。
                if (next.count >= 1 && (currentCount == 0 || nextEstimated >= Math.max(2D, currentCount * 1.15))) {
                    result.add(next.normalized);
                }
            } else if (nextEstimated >= minSupport &&
                    (currentCount == 0 || nextEstimated >= Math.max(minSupport, currentCount * 1.25))) {
                result.add(next.normalized);
            }
        }
        return result;
    }

    private boolean isEmergingNextYear(long trainCount, double actualCount, boolean holdout, int minSupport) {
        if (holdout) {
            return actualCount >= Math.max(2D, trainCount * 1.15);
        }
        return actualCount >= Math.max(minSupport, trainCount * 1.25);
    }

    private Match bestMatch(String predicted, Collection<TitleStats> actual, boolean holdout, double expansionFactor) {
        TitleStats best = null;
        double bestScore = 0D;
        for (TitleStats candidate : actual) {
            double score = titleSimilarity(predicted, candidate.normalized);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
            if (bestScore >= 0.999) {
                break;
            }
        }
        return new Match(best, bestScore);
    }

    public String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        String value = Normalizer.normalize(title, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
        value = BRACKETS.matcher(value).replaceAll(" ");
        value = LEVEL_WORDS.matcher(value).replaceAll(" ");
        value = NOISE.matcher(value).replaceAll(" ");
        value = value.replace("人工智能", "ai")
                .replace("大语言模型", "大模型")
                .replace("large language model", "llm")
                .replace("machine learning", "机器学习")
                .replace("deep learning", "深度学习")
                .replace("后端", "backend")
                .replace("前端", "frontend");
        value = NON_WORD.matcher(value).replaceAll(" ").trim().replaceAll("\\s+", " ");
        value = JOB_SUFFIX.matcher(value).replaceAll("").trim();
        return value.replaceAll("\\s+", " ");
    }

    public double titleSimilarity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return 0D;
        }
        if (a.equals(b)) {
            return 1D;
        }
        String compactA = a.replace(" ", "");
        String compactB = b.replace(" ", "");
        if (compactA.equals(compactB)) {
            return 0.98;
        }
        if (compactA.contains(compactB) || compactB.contains(compactA)) {
            double ratio = Math.min(compactA.length(), compactB.length()) / (double) Math.max(compactA.length(), compactB.length());
            return clamp01(0.76 + 0.20 * ratio);
        }
        Set<String> gramsA = bigrams(compactA);
        Set<String> gramsB = bigrams(compactB);
        if (gramsA.isEmpty() || gramsB.isEmpty()) {
            return 0D;
        }
        Set<String> intersection = new HashSet<>(gramsA);
        intersection.retainAll(gramsB);
        Set<String> union = new HashSet<>(gramsA);
        union.addAll(gramsB);
        return union.isEmpty() ? 0D : intersection.size() / (double) union.size();
    }

    private Set<String> bigrams(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text.length() == 1) {
            result.add(text);
            return result;
        }
        for (int i = 0; i < text.length() - 1; i++) {
            result.add(text.substring(i, i + 2));
        }
        return result;
    }

    private double momentum(long h1, long h2) {
        if (h1 + h2 == 0) {
            return 0D;
        }
        // 0.5 表示上下半年持平，>0.5 表示下半年加速。
        return clamp01(0.5 + 0.5 * ((h2 - h1) / (double) (h1 + h2)));
    }

    private double dataBalance(long h1, long h2) {
        long total = h1 + h2;
        if (total == 0) {
            return 0D;
        }
        return clamp01(1D - Math.abs(h1 - h2) / (double) total);
    }

    private double average(List<Map<String, Object>> rows, String key) {
        if (rows.isEmpty()) {
            return 0D;
        }
        double sum = 0D;
        int count = 0;
        for (Map<String, Object> row : rows) {
            Object value = row.get(key);
            if (value instanceof Number number) {
                sum += number.doubleValue();
                count++;
            }
        }
        return count == 0 ? 0D : sum / count;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Number number(Object value) {
        return value instanceof Number n ? n : 0;
    }

    private static double clamp01(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000D) / 1_000_000D;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000D) / 10_000D;
    }

    private record Match(TitleStats stats, double similarity) {
    }

    private record ForecastCandidate(
            TitleStats stats,
            long previousCount,
            double novelty,
            double momentum,
            double forecastScore,
            double confidence
    ) {
    }

    private static final class TitleStats {
        private final String normalized;
        private final String displayTitle;
        private final long count;
        private final long companyCount;
        private final long h1;
        private final long h2;

        private TitleStats(
                String normalized,
                String displayTitle,
                long count,
                long companyCount,
                long h1,
                long h2
        ) {
            this.normalized = normalized;
            this.displayTitle = displayTitle;
            this.count = count;
            this.companyCount = companyCount;
            this.h1 = h1;
            this.h2 = h2;
        }

        private TitleStats merge(TitleStats other) {
            String title = this.count >= other.count ? this.displayTitle : other.displayTitle;
            return new TitleStats(
                    normalized,
                    title,
                    count + other.count,
                    companyCount + other.companyCount,
                    h1 + other.h1,
                    h2 + other.h2
            );
        }
    }
}
