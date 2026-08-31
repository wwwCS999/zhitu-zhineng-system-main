package com.zhitu.controller;

import com.zhitu.common.ApiResponse;
import com.zhitu.dto.Requests;
import com.zhitu.service.TemporalDatasetService;
import com.zhitu.service.TemporalForecastService;
import jakarta.validation.Valid;
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
@RequestMapping("/api/temporal")
public class TemporalForecastController {

    private final TemporalDatasetService datasetService;
    private final TemporalForecastService forecastService;

    public TemporalForecastController(
            TemporalDatasetService datasetService,
            TemporalForecastService forecastService
    ) {
        this.datasetService = datasetService;
        this.forecastService = forecastService;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(datasetService.overview());
    }

    @GetMapping("/years")
    public ApiResponse<List<Map<String, Object>>> years() {
        return ApiResponse.ok(datasetService.yearStats());
    }

    @GetMapping("/holdout/sample")
    public ApiResponse<List<Map<String, Object>>> holdoutSample(
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(datasetService.holdoutSample(limit));
    }

    @PostMapping("/holdout/prepare")
    public ApiResponse<Map<String, Object>> prepareHoldout(
            @RequestBody(required = false) Requests.HoldoutRequest request
    ) {
        if (request == null) {
            return ApiResponse.ok("2026 测试集已锁定", datasetService.prepareDefaultHoldout(false));
        }
        int year = request.year() == null ? datasetService.defaultHoldoutYear() : request.year();
        int size = request.size() == null ? datasetService.defaultHoldoutSize() : request.size();
        boolean reset = Boolean.TRUE.equals(request.reset());
        return ApiResponse.ok(
                "测试集已锁定",
                datasetService.prepareHoldout(year, size, request.seed(), reset)
        );
    }

    @PostMapping("/backtest")
    public ApiResponse<Map<String, Object>> backtest(
            @Valid @RequestBody Requests.TemporalBacktestRequest request
    ) {
        int topK = request.topK() == null ? 30 : request.topK();
        int minSupport = request.minSupport() == null ? 3 : request.minSupport();
        return ApiResponse.ok(
                "年度滚动预测与回测完成",
                forecastService.backtest(request.startYear(), request.endYear(), topK, minSupport)
        );
    }

    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> runs(
            @RequestParam(defaultValue = "60") int limit
    ) {
        return ApiResponse.ok(forecastService.runs(limit));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<Map<String, Object>> runDetail(@PathVariable String runId) {
        return ApiResponse.ok(forecastService.runDetail(runId));
    }
}
