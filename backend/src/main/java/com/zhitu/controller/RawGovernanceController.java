package com.zhitu.controller;

import com.zhitu.common.ApiResponse;
import com.zhitu.dto.Requests;
import com.zhitu.service.GovernedJobEditService;
import com.zhitu.service.RawJobGovernanceService;
import com.zhitu.service.RawJobSchemaService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 百万 JD 治理与治理后数据管理接口。
 *
 * V7 关键调整：
 * 1. “治理任务状态”与“人工数据管理”彻底解耦；
 * 2. progress 接口只读取治理运行表，供前端高频轮询；
 * 3. jobs 接口永久可用，只要 MySQL 可连接，就可以查询、修改、删除已治理 JD；
 * 4. 不要求治理任务处于 RUNNING，也不要求全部 180 万数据治理完成。
 */
@RestController
@RequestMapping("/api/raw-governance")
public class RawGovernanceController {

    private final RawJobGovernanceService governance;
    private final RawJobSchemaService schema;
    private final GovernedJobEditService editor;

    public RawGovernanceController(
            RawJobGovernanceService governance,
            RawJobSchemaService schema,
            GovernedJobEditService editor
    ) {
        this.governance = governance;
        this.schema = schema;
        this.editor = editor;
    }

    /**
     * 完整统计接口。包含百万表统计、质量摘要、字段映射等。
     * 该接口可能比 progress 慢，因此前端只在页面首次加载/手动刷新时调用。
     */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(governance.overview());
    }

    /**
     * 轻量级治理进度接口。
     * 只基于 zhitu_governance_run + 1000 条 holdout 统计，不扫描百万原表，
     * 可供前端每 2 秒轮询。
     */
    @GetMapping("/progress")
    public ApiResponse<Map<String, Object>> progress() {
        return ApiResponse.ok(governance.progressSnapshot());
    }

    @GetMapping("/schema")
    public ApiResponse<Map<String, Object>> schema() {
        return ApiResponse.ok(schema.describe());
    }

    /**
     * 兼容旧版“最近治理抽样”接口。
     * V7 前端不再依赖它做人工数据管理，保留是为了旧页面/旧调用不报错。
     */
    @GetMapping("/samples")
    public ApiResponse<List<Map<String, Object>>> samples(@RequestParam(defaultValue = "80") int limit) {
        return ApiResponse.ok(governance.samples(limit));
    }

    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> runs(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(governance.runs(limit));
    }

    @PostMapping("/start")
    public ApiResponse<Map<String, Object>> start(
            @RequestParam(defaultValue = "false") boolean reset,
            @RequestParam(required = false) Integer batchSize
    ) {
        return ApiResponse.ok("百万 JD 治理任务已启动", governance.start(reset, batchSize));
    }

    @PostMapping("/pause")
    public ApiResponse<Map<String, Object>> pause() {
        return ApiResponse.ok(governance.pause());
    }

    @PostMapping("/resume")
    public ApiResponse<Map<String, Object>> resume(@RequestParam(required = false) Integer batchSize) {
        return ApiResponse.ok("治理任务已继续", governance.resume(batchSize));
    }

    /**
     * 永久可用的治理后 JD 分页查询接口。
     * 与 RUNNING / PAUSED / COMPLETED / IDLE 状态无关。
     */
    @GetMapping("/jobs")
    public ApiResponse<Map<String, Object>> jobs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int size,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "ACTIVE") String state
    ) {
        return ApiResponse.ok(editor.listJobs(page, size, year, keyword, state));
    }

    @GetMapping("/jobs/{rawJobId}")
    public ApiResponse<Map<String, Object>> job(@PathVariable long rawJobId) {
        return ApiResponse.ok(editor.detail(rawJobId));
    }

    @PutMapping("/jobs/{rawJobId}")
    public ApiResponse<Map<String, Object>> updateJob(
            @PathVariable long rawJobId,
            @RequestBody Requests.GovernedJobUpdate request
    ) {
        return ApiResponse.ok("JD 已更新；原始 dataset_job_raw 保持不变", editor.updateJob(rawJobId, request));
    }

    @DeleteMapping("/jobs/{rawJobId}")
    public ApiResponse<Map<String, Object>> deleteJob(@PathVariable long rawJobId) {
        return ApiResponse.ok("JD 已从分析层删除；原始记录仍保留", editor.deleteJob(rawJobId));
    }

    @PostMapping("/jobs/{rawJobId}/skills")
    public ApiResponse<Map<String, Object>> addSkill(
            @PathVariable long rawJobId,
            @Valid @RequestBody Requests.GovernedSkillCreate request
    ) {
        return ApiResponse.ok("能力项已添加", editor.addSkill(rawJobId, request));
    }

    @DeleteMapping("/jobs/{rawJobId}/skills/{skillId}")
    public ApiResponse<Map<String, Object>> deleteSkill(
            @PathVariable long rawJobId,
            @PathVariable long skillId
    ) {
        return ApiResponse.ok("能力项已删除", editor.deleteSkill(rawJobId, skillId));
    }
}
