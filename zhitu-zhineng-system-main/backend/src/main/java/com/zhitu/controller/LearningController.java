package com.zhitu.controller;

import com.zhitu.common.ApiResponse;
import com.zhitu.dto.Requests;
import com.zhitu.service.LearningPlanningService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/learning")
public class LearningController {

    private final LearningPlanningService service;

    public LearningController(LearningPlanningService service) {
        this.service = service;
    }

    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(@Valid @RequestBody Requests.LearningRequest request) {
        return ApiResponse.ok(
                "学习路径已生成",
                service.generate(
                        request.matchId(),
                        request.weeks() == null ? 12 : request.weeks(),
                        request.hoursPerWeek() == null ? 8 : request.hoursPerWeek(),
                        request.planModes(),
                        request.planMode()
                )
        );
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/{id}/optimize")
    public ApiResponse<Map<String, Object>> optimize(@PathVariable long id) {
        return ApiResponse.ok("培养方案已优化", service.optimize(id));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> paths() {
        return ApiResponse.ok(service.paths());
    }
}
