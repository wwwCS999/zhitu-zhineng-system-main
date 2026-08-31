package com.zhitu.service;

import com.zhitu.dto.AgentAnswer;
import com.zhitu.repository.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 六智能体总控编排器。
 *
 * V10：完整流水线改为“当前治理快照”模式。
 * 只要已经治理至少 100 条 JD，就允许更新总览、探新、验证准备、能力演化、图谱和匹配岗位目录。
 * 每次用户点击“更新当前快照流水线”时重新读取当前已治理数据；不会等待百万治理全部完成。
 */
@Service
public class AgentOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorService.class);

    private final Store store;
    private final RawJobGovernanceService rawGovernance;
    private final TemporalDatasetService temporalDataset;
    private final EmergingRoleService emerging;
    private final EvolutionService evolution;
    private final GraphService graph;
    private final DashboardService dashboard;
    private final AgentQuestionAnswerService questionAnswer;

    public AgentOrchestratorService(
            Store store,
            RawJobGovernanceService rawGovernance,
            TemporalDatasetService temporalDataset,
            EmergingRoleService emerging,
            EvolutionService evolution,
            GraphService graph,
            DashboardService dashboard,
            AgentQuestionAnswerService questionAnswer
    ) {
        this.store = store;
        this.rawGovernance = rawGovernance;
        this.temporalDataset = temporalDataset;
        this.emerging = emerging;
        this.evolution = evolution;
        this.graph = graph;
        this.dashboard = dashboard;
        this.questionAnswer = questionAnswer;
    }

    public Map<String, Object> runFull() {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> steps = new ArrayList<>();

        Map<String, Object> snapshot = safeAnalysisSnapshot();
        if (!Boolean.TRUE.equals(snapshot.get("snapshotReady"))) {
            steps.add(degradedStep(
                    "data-governance-agent",
                    "snapshot-readiness-check",
                    snapshot,
                    "治理快照未达到分析准入，已阻止重型流水线执行"
            ));
            return degradedPipelineResult(start, snapshot, steps,
                    "当前治理快照不可用于完整流水线。请先在数据治理智能体完成至少 "
                            + snapshot.getOrDefault("analysisMinGovernedRows", 100)
                            + " 条有效 JD 治理，或检查 MySQL 治理库连接。");
        }

        if (dashboardFastPipelineEnabled()) {
            steps.add(run("data-governance-agent", "snapshot-readiness-check", () -> {
                Map<String, Object> result = new LinkedHashMap<>(snapshot);
                result.put("status", "SNAPSHOT_READY");
                result.put("executionMode", "FAST_DASHBOARD_PIPELINE");
                return result;
            }));
            steps.add(run("dashboard-agent", "refresh-executive-overview", () -> {
                Map<String, Object> overview = new LinkedHashMap<>(dashboard.overview(true));
                overview.put("status", "OVERVIEW_READY");
                return overview;
            }));
            steps.add(run("emerging-role-agent", "read-current-candidate-pool", () -> {
                List<Map<String, Object>> candidates = emerging.candidates();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "CACHE_READY");
                result.put("candidateCount", candidates.size());
                result.put("note", "Dashboard pipeline reads existing candidates only. Run deep discovery from the emerging role page.");
                return result;
            }));
            steps.add(run("evolution-agent", "read-current-evolution-events", () -> {
                List<Map<String, Object>> events = evolution.events();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "CACHE_READY");
                result.put("eventCount", events.size());
                result.put("note", "Dashboard pipeline does not trigger heavy LLM calibration. Run evolution analysis from the evolution page.");
                return result;
            }));
            steps.add(run("capability-graph-agent", "read-current-role-directory", () -> {
                List<Map<String, Object>> roles = graph.roles();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "ROLE_DIRECTORY_READY");
                result.put("roleCount", roles.size());
                return result;
            }));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", pipelineStatus(steps));
            result.put("executionMode", "FAST_DASHBOARD_PIPELINE");
            result.put("dataSource", "MYSQL_GOVERNED_MILLION_JD");
            result.put("snapshot", safeAnalysisSnapshot());
            result.put("durationMs", System.currentTimeMillis() - start);
            result.put("steps", steps);
            return result;
        }

        steps.add(run("数据治理智能体", "当前治理快照检查", () -> {
            Map<String, Object> result = new LinkedHashMap<>(snapshot);
            result.put("status", "SNAPSHOT_READY");
            result.put("note", "达到最少 100 条治理记录后即可运行；每次更新都会读取最新快照。 ");
            return result;
        }));

        steps.add(run("岗位洞察智能体", "当前 JD 结构化解析结果确认", () -> {
            Map<String, Object> overview = rawGovernance.overview();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", Boolean.TRUE.equals(overview.get("fullGovernanceComplete"))
                    ? "FULL_READY"
                    : "SNAPSHOT_READY");
            result.put("governedRows", overview.get("governedRows"));
            result.put("validRows", overview.get("validRows"));
            result.put("skillRelations", overview.get("skillRelations"));
            result.put("snapshotVersion", overview.get("snapshotVersion"));
            result.put("analysisScope", overview.get("analysisScope"));
            result.put("quality", overview.get("quality"));
            result.put("source", "MYSQL_GOVERNED_MILLION_JD");
            return result;
        }));

        steps.add(run("岗位洞察智能体", "基于当前快照更新新岗位发现", emerging::discover));

        steps.add(run("可信审核智能体", "年度验证数据快照准备", () -> {
            Map<String, Object> result = new LinkedHashMap<>(temporalDataset.overview());
            result.put("note", "验证页可直接使用当前已治理快照运行；缺少数据的年份窗口会跳过。全量治理完成后再运行一次作为最终结果。 ");
            return result;
        }));

        steps.add(run("岗位洞察智能体", "基于当前快照更新岗位能力演化", evolution::analyze));

        steps.add(run("能力图谱与演化智能体", "基于当前快照更新能力图谱", () -> graph.panorama("", "", 400)));

        steps.add(run("画像匹配智能体", "同步当前岗位目录供匹配使用", () -> {
            List<Map<String, Object>> roles = graph.roles();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("roleCount", roles.size());
            result.put("snapshot", rawGovernance.analysisSnapshot());
            result.put("note", "匹配页重新选择岗位并执行诊断时，会再次从当前治理快照聚合该岗位技能要求。 ");
            return result;
        }));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", pipelineStatus(steps));
        result.put("dataSource", "MYSQL_GOVERNED_MILLION_JD");
        result.put("snapshot", safeAnalysisSnapshot());
        result.put("durationMs", System.currentTimeMillis() - start);
        result.put("steps", steps);
        return result;
    }

    private Map<String, Object> run(
            String agent,
            String task,
            Supplier<Map<String, Object>> action
    ) {
        long started = System.currentTimeMillis();
        try {
            Map<String, Object> output = action.get();
            long duration = System.currentTimeMillis() - started;
            record(agent, task, "SUCCESS", output, duration);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("agent", agent);
            result.put("task", task);
            result.put("status", "SUCCESS");
            result.put("output", output);
            return result;
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - started;
            Map<String, Object> failure = Map.of("error", safe(ex.getMessage()));
            record(agent, task, "FAILED", failure, duration);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("agent", agent);
            result.put("task", task);
            result.put("status", "FAILED");
            result.put("error", safe(ex.getMessage()));
            return result;
        }
    }

    private boolean dashboardFastPipelineEnabled() {
        return true;
    }

    private void record(
            String agent,
            String task,
            String status,
            Object output,
            long durationMs
    ) {
        try {
            store.insert(
                    "INSERT INTO agent_run(agent_name,task_name,status,input_summary,output_summary,duration_ms) " +
                            "VALUES(:agent,:task,:status,'{}',:output,:duration)",
                    Map.of(
                            "agent", agent,
                            "task", task,
                            "status", status,
                            "output", String.valueOf(output),
                            "duration", durationMs
                    )
            );
        } catch (Exception ex) {
            log.warn("记录智能体运行日志失败，但不影响流水线返回：agent={}, task={}, error={}",
                    agent, task, rootMessage(ex));
        }
    }

    public AgentAnswer chat(String message) {
        return chat(message, "default");
    }

    public AgentAnswer chat(String message, String sessionId) {
        return questionAnswer.answer(message, sessionId);
    }

    public Map<String, Object> chatStatus() {
        return questionAnswer.status();
    }

    public List<Map<String, Object>> runs() {
        return store.list(
                "SELECT * FROM agent_run ORDER BY id DESC LIMIT 100",
                Map.of()
        );
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "未知错误";
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private Map<String, Object> safeAnalysisSnapshot() {
        try {
            return rawGovernance.analysisSnapshot();
        } catch (Exception ex) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("processedRows", 0);
            result.put("validRows", 0);
            result.put("targetRows", 0);
            result.put("remainingRows", 0);
            result.put("holdoutRows", 0);
            result.put("holdoutTarget", 0);
            result.put("snapshotReady", false);
            result.put("readyForAnalysis", false);
            result.put("fullGovernanceComplete", false);
            result.put("analysisMinGovernedRows", 100);
            result.put("snapshotVersion", 0);
            result.put("analysisScope", "UNAVAILABLE");
            result.put("runStatus", "UNAVAILABLE");
            result.put("runId", "");
            result.put("error", rootMessage(ex));
            result.put("suggestion", "请检查 MySQL 治理库连接、RAW_DB_* 配置和治理派生表是否已初始化。");
            log.warn("读取治理快照失败，流水线进入降级状态：{}", rootMessage(ex));
            return result;
        }
    }

    private Map<String, Object> degradedStep(
            String agent,
            String task,
            Map<String, Object> snapshot,
            String note
    ) {
        Map<String, Object> output = new LinkedHashMap<>(snapshot);
        output.put("note", note);
        output.putIfAbsent("status", "DEGRADED");
        record(agent, task, "DEGRADED", output, 0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", agent);
        result.put("task", task);
        result.put("status", "DEGRADED");
        result.put("output", output);
        return result;
    }

    private Map<String, Object> degradedPipelineResult(
            long start,
            Map<String, Object> snapshot,
            List<Map<String, Object>> steps,
            String message
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "DEGRADED");
        result.put("executionMode", "FAST_DASHBOARD_PIPELINE");
        result.put("dataSource", "MYSQL_GOVERNED_MILLION_JD");
        result.put("snapshot", snapshot);
        result.put("durationMs", System.currentTimeMillis() - start);
        result.put("message", message);
        result.put("steps", steps);
        return result;
    }

    private static String pipelineStatus(List<Map<String, Object>> steps) {
        boolean hasFailure = steps.stream()
                .map(step -> String.valueOf(step.getOrDefault("status", "")))
                .anyMatch(status -> "FAILED".equalsIgnoreCase(status) || "DEGRADED".equalsIgnoreCase(status));
        return hasFailure ? "DEGRADED" : "COMPLETED";
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
}
