package com.zhitu.controller;

import com.zhitu.common.ApiResponse;
import com.zhitu.service.JobInsightService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobInsightService service;

    public JobController(JobInsightService service) {
        this.service = service;
    }

    @PostMapping("/parse-all")
    public ApiResponse<Map<String, Object>> parseAll() {
        return ApiResponse.ok("批量解析完成", service.parseAll());
    }

    @PostMapping("/{id}/parse")
    public ApiResponse<Map<String, Object>> parse(@PathVariable long id) {
        return ApiResponse.ok(service.parse(id));
    }

    @PostMapping("/parse-text")
    public ApiResponse<Map<String, Object>> parseText(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(
            "JD 解析完成",
            service.parseText(body.getOrDefault("title", ""), body.getOrDefault("description", ""))
        );
    }

    @GetMapping("/parser-evaluation")
    public ApiResponse<Map<String, Object>> parserEvaluation() {
        return ApiResponse.ok(service.parserEvaluationSummary());
    }

    @PostMapping("/parser-evaluation/run")
    public ApiResponse<Map<String, Object>> runParserEvaluation() {
        return ApiResponse.ok("JD 解析验收完成", service.runParserEvaluation());
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> jobs(@RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(service.jobs(limit));
    }

    @GetMapping("/roles/{id}")
    public ApiResponse<Map<String, Object>> role(@PathVariable long id) {
        return ApiResponse.ok(service.role(id));
    }
}
