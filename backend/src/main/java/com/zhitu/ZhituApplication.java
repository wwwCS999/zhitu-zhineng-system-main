package com.zhitu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@EnableScheduling
@SpringBootApplication
public class ZhituApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ZhituApplication.class);
        Map<String, Object> dotEnv = loadDotEnvCandidates();
        if (!dotEnv.isEmpty()) {
            // Default properties have lower priority than command-line arguments,
            // JVM -D properties and real environment variables.
            application.setDefaultProperties(dotEnv);
        }
        application.run(args);
    }

    static Map<String, Object> loadDotEnvCandidates() {
        Path workingDirectory = Path.of(System.getProperty("user.dir", "."))
                .toAbsolutePath()
                .normalize();
        List<Path> candidates = new ArrayList<>();
        candidates.add(workingDirectory.resolve(".env"));
        candidates.add(workingDirectory.resolve("backend").resolve(".env"));
        if (workingDirectory.getFileName() != null
                && "backend".equalsIgnoreCase(workingDirectory.getFileName().toString())
                && workingDirectory.getParent() != null) {
            candidates.add(workingDirectory.getParent().resolve(".env"));
        }
        return loadDotEnv(candidates);
    }

    static Map<String, Object> loadDotEnv(List<Path> candidates) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                for (String rawLine : Files.readAllLines(candidate, StandardCharsets.UTF_8)) {
                    String line = rawLine.strip();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    if (line.startsWith("export ")) {
                        line = line.substring("export ".length()).strip();
                    }
                    int separator = line.indexOf('=');
                    if (separator <= 0) {
                        continue;
                    }
                    String key = line.substring(0, separator).strip();
                    String value = line.substring(separator + 1).strip();
                    if (!key.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
                        continue;
                    }
                    if (value.length() >= 2
                            && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    properties.putIfAbsent(key, value);
                }
            } catch (IOException ignored) {
                // A malformed/unreadable local .env must not prevent startup.
            }
        }
        return properties;
    }
}
