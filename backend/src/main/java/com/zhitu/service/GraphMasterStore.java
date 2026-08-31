package com.zhitu.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 全量岗位能力“母图”内存仓库。
 *
 * 设计目标：
 * 1. 图谱页面打开时不再扫描百万 JD；
 * 2. Spring Boot 启动时优先从磁盘读取已生成的 master-graph.json；
 * 3. “更新全量母图”在后台构建，新图成功后再原子替换旧图；
 * 4. 构建期间旧图始终可以继续使用；
 * 5. 如果项目 resources/graph/master-graph.json 中放入最终比赛母图，也可作为只读内置兜底。
 */
@Service
public class GraphMasterStore {

    private static final Logger log = LoggerFactory.getLogger(GraphMasterStore.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final Path snapshotFile;
    private final AtomicReference<Map<String, Object>> snapshotRef =
            new AtomicReference<>(emptySnapshot("尚未生成全量母图"));

    public GraphMasterStore(
            ObjectMapper objectMapper,
            @Value("${app.graph.master.snapshot-file:data/graph/master-graph.json}") String configuredPath
    ) {
        this.objectMapper = objectMapper;
        this.snapshotFile = resolveSnapshotPath(configuredPath);
    }

    @PostConstruct
    public void loadOnStartup() {
        if (loadFromDisk()) {
            return;
        }

        loadBundledFallback();
    }

    public boolean hasSnapshot() {
        return booleanValue(snapshotRef.get().get("available"));
    }

    public Map<String, Object> snapshot() {
        return snapshotRef.get();
    }

    public long snapshotVersion() {
        Object metaValue = snapshotRef.get().get("meta");
        if (!(metaValue instanceof Map<?, ?> meta)) {
            return 0L;
        }
        return longValue(meta.get("snapshotVersion"));
    }

    public String generatedAt() {
        Object metaValue = snapshotRef.get().get("meta");
        if (!(metaValue instanceof Map<?, ?> meta)) {
            return "";
        }
        Object value = meta.get("generatedAt");
        return value == null ? "" : String.valueOf(value);
    }

    public synchronized void replaceAndPersist(Map<String, Object> snapshot) {
        Map<String, Object> normalized = new LinkedHashMap<>(snapshot);
        normalized.put("available", true);

        try {
            Files.createDirectories(snapshotFile.getParent());

            Path temp = snapshotFile.resolveSibling(snapshotFile.getFileName() + ".tmp");
            objectMapper.writeValue(temp.toFile(), normalized);

            try {
                Files.move(
                        temp,
                        snapshotFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temp,
                        snapshotFile,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            snapshotRef.set(normalized);
            log.info(
                    "岗位能力母图已持久化：path={}, snapshotVersion={}, generatedAt={}",
                    snapshotFile,
                    snapshotVersion(),
                    generatedAt()
            );
        } catch (Exception e) {
            throw new IllegalStateException("母图持久化失败：" + rootMessage(e), e);
        }
    }

    public Map<String, Object> storeInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", hasSnapshot());
        result.put("snapshotVersion", snapshotVersion());
        result.put("generatedAt", generatedAt());
        result.put("snapshotFile", snapshotFile.toString());
        result.put("fileExists", Files.exists(snapshotFile));
        return result;
    }

    private boolean loadFromDisk() {
        if (!Files.exists(snapshotFile)) {
            return false;
        }

        try {
            Map<String, Object> snapshot = objectMapper.readValue(snapshotFile.toFile(), MAP_TYPE);
            snapshot.put("available", true);
            snapshotRef.set(snapshot);
            log.info(
                    "已从磁盘加载岗位能力母图：path={}, snapshotVersion={}",
                    snapshotFile,
                    snapshotVersion()
            );
            return true;
        } catch (Exception e) {
            log.warn("读取磁盘母图失败，将尝试内置兜底：{}", rootMessage(e));
            return false;
        }
    }

    private void loadBundledFallback() {
        ClassPathResource resource = new ClassPathResource("graph/master-graph.json");
        if (!resource.exists()) {
            return;
        }

        try (InputStream input = resource.getInputStream()) {
            Map<String, Object> snapshot = objectMapper.readValue(input, MAP_TYPE);
            snapshot.put("available", true);
            snapshotRef.set(snapshot);
            log.info("已加载 resources/graph/master-graph.json 内置母图，snapshotVersion={}", snapshotVersion());
        } catch (Exception e) {
            log.warn("加载内置母图失败：{}", rootMessage(e));
        }
    }

    private static Path resolveSnapshotPath(String configuredPath) {
        Path configured = Path.of(configuredPath);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }

        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path root = cwd;

        Path fileName = cwd.getFileName();
        if (fileName != null && "backend".equalsIgnoreCase(fileName.toString()) && cwd.getParent() != null) {
            root = cwd.getParent();
        }

        return root.resolve(configured).normalize();
    }

    private static Map<String, Object> emptySnapshot(String message) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("snapshotVersion", 0L);
        meta.put("generatedAt", "");
        meta.put("scope", "EMPTY");
        meta.put("message", message);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("nodes", java.util.List.of());
        result.put("links", java.util.List.of());
        result.put("summary", Map.of(
                "roleCount", 0,
                "skillCount", 0,
                "nodeCount", 0,
                "linkCount", 0
        ));
        result.put("stacks", java.util.List.of());
        result.put("palette", java.util.List.of(
                "#BFD7D2", "#51999F", "#4198AC", "#79C0CD",
                "#D9CB92", "#ECBC66", "#E49E58", "#ED805A"
        ));
        result.put("meta", meta);
        return result;
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
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
