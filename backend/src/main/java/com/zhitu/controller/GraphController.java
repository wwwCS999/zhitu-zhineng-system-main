package com.zhitu.controller;

import com.zhitu.common.ApiResponse;
import com.zhitu.service.GraphRagService;
import com.zhitu.service.GraphService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 岗位能力图谱接口。
 *
 * refresh=false：优先读取相同筛选条件最近一次成功图谱缓存；
 * refresh=true ：用户点击“构建 / 更新图谱”时基于当前治理快照重新计算。
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphService graphService;
    private final GraphRagService graphRagService;

    public GraphController(GraphService graphService, GraphRagService graphRagService) {
        this.graphService = graphService;
        this.graphRagService = graphRagService;
    }

    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options(
            @RequestParam(defaultValue = "false") boolean refresh
    ) {
        return ApiResponse.ok(graphService.options(refresh));
    }

    @GetMapping("/panorama")
    public ApiResponse<Map<String, Object>> panorama(
            @RequestParam(required = false) String techStack,
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "650") int limit,
            @RequestParam(defaultValue = "1") int minEvidence,
            @RequestParam(defaultValue = "false") boolean refresh
    ) {
        return ApiResponse.ok(
                graphService.panorama(
                        techStack,
                        level,
                        limit,
                        minEvidence,
                        refresh
                )
        );
    }

    @GetMapping("/roles")
    public ApiResponse<List<Map<String, Object>>> roles() {
        return ApiResponse.ok(graphService.roles());
    }

    @PostMapping("/ask")
    public ApiResponse<Map<String, Object>> ask(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(graphRagService.ask(body.getOrDefault("question", "")));
    }
}
