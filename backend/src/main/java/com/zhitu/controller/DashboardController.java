package com.zhitu.controller;

import com.zhitu.common.ApiResponse;
import com.zhitu.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统总览接口。
 *
 * refresh=false：优先返回最近一次已经生成的分析快照，页面打开更快。
 * refresh=true ：用户点击“刷新”时重新聚合当前已治理 JD 快照。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestParam(defaultValue = "false") boolean refresh
    ) {
        return ApiResponse.ok(service.overview(refresh));
    }
}
