package com.zhitu.controller;

import com.zhitu.common.ApiResponse;
import com.zhitu.dto.AgentAnswer;
import com.zhitu.dto.Requests;
import com.zhitu.service.AgentOrchestratorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AgentController {

    private final AgentOrchestratorService service;

    public AgentController(AgentOrchestratorService service) {
        this.service = service;
    }

    @PostMapping("/agent/chat")
    public ApiResponse<AgentAnswer> chat(@Valid @RequestBody Requests.ChatRequest request) {
        return ApiResponse.ok(service.chat(request.message(), request.sessionId()));
    }

    @GetMapping("/agent/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(service.chatStatus());
    }

    @GetMapping("/agent/runs")
    public ApiResponse<List<Map<String, Object>>> runs() {
        return ApiResponse.ok(service.runs());
    }

    @PostMapping("/orchestrator/run-full-pipeline")
    public ApiResponse<Map<String, Object>> run() {
        return ApiResponse.ok("完整流水线执行完成", service.runFull());
    }
}
