package com.zhitu.service;

import com.zhitu.ai.AiClient;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 基于岗位能力图谱的 RAG 问答：以图谱（岗位—技能关系）为知识库，
 * 检索与问题相关的岗位/技能子图作为证据，再由大模型基于证据生成回答。
 */
@Service
public class GraphRagService {

    private static final String SYSTEM = """
            你是“职途智配”的岗位能力图谱问答智能体。
            你必须只依据系统提供的图谱证据回答，禁止编造岗位或技能。
            图谱证据由“岗位节点”与“技能点节点”组成，关系为“岗位需要某技能”。
            回答要求：
            1. 忠实引用证据中的岗位名和技能名，不要臆造；
            2. 在关键结论后使用 [证据1]、[证据2] 编号，编号对应输入证据顺序；
            3. 证据不足时明确说“当前图谱不足以判断”，并说明还缺什么；
            4. 不要声称执行了证据之外的数据库查询、审核或预测。
            """;

    private final GraphService graphService;
    private final AiClient ai;

    public GraphRagService(GraphService graphService, AiClient ai) {
        this.graphService = graphService;
        this.ai = ai;
    }

    public Map<String, Object> ask(String question) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) {
            return Map.of("answer", "请输入要查询的问题。", "evidence", List.of());
        }

        Map<String, Object> graph;
        try {
            graph = graphService.panorama("", "", 1600, 1, false);
        } catch (Exception e) {
            graph = Map.of();
        }
        List<Map<String, Object>> nodes = list(graph.get("nodes"));
        List<Map<String, Object>> links = list(graph.get("links"));

        if (nodes.isEmpty()) {
            return Map.of(
                    "answer", "岗位能力图谱尚未构建。请先在“图谱构建”中生成图谱后再提问。",
                    "evidence", List.of()
            );
        }

        Map<String, Map<String, Object>> nodeById = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            nodeById.put(text(node.get("id")), node);
        }

        // 1) 检索：节点名（岗位或技能）出现在问题中即命中。
        String normalizedQuestion = normalize(q);
        Set<String> seedIds = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            String name = normalize(text(node.get("name")));
            if (name.length() >= 2 && normalizedQuestion.contains(name)) {
                seedIds.add(text(node.get("id")));
            }
        }

        // 2) 一跳扩展：命中节点的邻居。
        Set<String> expanded = new LinkedHashSet<>(seedIds);
        for (Map<String, Object> link : links) {
            String source = text(link.get("source"));
            String target = text(link.get("target"));
            if (seedIds.contains(source)) expanded.add(target);
            if (seedIds.contains(target)) expanded.add(source);
        }

        // 3) 证据：命中/邻居中的岗位节点 + 其技能列表。
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            String id = text(node.get("id"));
            if (!expanded.contains(id) || !"ROLE".equals(text(node.get("type")))) {
                continue;
            }
            List<String> skills = new ArrayList<>();
            for (Map<String, Object> link : links) {
                String source = text(link.get("source"));
                String target = text(link.get("target"));
                if (!id.equals(source) && !id.equals(target)) continue;
                String otherId = id.equals(source) ? target : source;
                Map<String, Object> other = nodeById.get(otherId);
                if (other != null && "SKILL".equals(text(other.get("type")))) {
                    boolean bonus = "BONUS".equalsIgnoreCase(text(link.get("type")))
                            || "PREFERRED".equalsIgnoreCase(text(link.get("type")));
                    skills.add(text(other.get("name")) + (bonus ? "（加分）" : ""));
                }
            }
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("evidenceType", "graph_role");
            ev.put("role", text(node.get("name")));
            ev.put("stack", text(node.get("stack")));
            ev.put("skills", skills);
            evidence.add(ev);
        }

        if (evidence.isEmpty()) {
            return Map.of(
                    "answer", "当前图谱中没有检索到与问题相关的岗位或技能，请换一个更具体的岗位名或技能名提问。",
                    "evidence", List.of()
            );
        }

        String answer = generateAnswer(q, evidence);
        return Map.of("answer", answer, "evidence", evidence);
    }

    private String generateAnswer(String question, List<Map<String, Object>> evidence) {
        String context = buildContext(evidence);
        String user = "问题：" + question + "\n\n图谱证据：\n" + context;
        if (ai.enabled()) {
            try {
                Optional<String> generated = ai.complete(SYSTEM, user);
                if (generated.isPresent() && !generated.get().isBlank()) {
                    return generated.get();
                }
            } catch (Exception ignored) {
                // 大模型调用失败，降级为检索摘要
            }
        }
        return retrievalSummary(evidence);
    }

    private String buildContext(List<Map<String, Object>> evidence) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < evidence.size(); i++) {
            Map<String, Object> ev = evidence.get(i);
            sb.append("[证据").append(i + 1).append("] 岗位「")
                    .append(ev.get("role")).append("」（").append(ev.get("stack")).append("）技能：")
                    .append(String.join("、", skills(ev))).append("\n");
        }
        return sb.toString();
    }

    private String retrievalSummary(List<Map<String, Object>> evidence) {
        StringBuilder sb = new StringBuilder("根据岗位能力图谱检索到以下相关证据：\n");
        for (int i = 0; i < evidence.size(); i++) {
            Map<String, Object> ev = evidence.get(i);
            sb.append("[证据").append(i + 1).append("] ").append(ev.get("role"))
                    .append("（").append(ev.get("stack")).append("）：")
                    .append(String.join("、", skills(ev))).append("\n");
        }
        sb.append("\n配置 AI_API_KEY 后，将自动基于以上图谱证据生成自然语言回答。");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> skills(Map<String, Object> ev) {
        Object value = ev.get("skills");
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) result.add(String.valueOf(item));
            return result;
        }
        return List.of();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<Map<String, Object>> list(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                map.forEach((k, v) -> row.put(String.valueOf(k), v));
                result.add(row);
            }
        }
        return result;
    }
}