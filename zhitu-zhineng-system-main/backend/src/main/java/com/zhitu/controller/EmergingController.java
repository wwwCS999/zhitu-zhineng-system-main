package com.zhitu.controller;

import com.zhitu.common.ApiResponse;
import com.zhitu.service.EmergingRoleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/emerging")
public class EmergingController {

    private final EmergingRoleService service;

    public EmergingController(EmergingRoleService service) {
        this.service = service;
    }

    @GetMapping("/years")
    public ApiResponse<List<Map<String, Object>>> years() {
        return ApiResponse.ok(service.availableTargetYears());
    }

    @PostMapping("/discover")
    public ApiResponse<Map<String, Object>> discover(@RequestParam int targetYear) {
        return ApiResponse.ok(
                targetYear + " 年高潜岗位预测完成",
                service.discover(targetYear)
        );
    }

    @GetMapping("/candidates")
    public ApiResponse<List<Map<String, Object>>> candidates(@RequestParam int targetYear) {
        return ApiResponse.ok(service.candidates(targetYear));
    }
}
