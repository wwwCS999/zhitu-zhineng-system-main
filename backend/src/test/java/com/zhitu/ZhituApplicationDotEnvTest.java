package com.zhitu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ZhituApplicationDotEnvTest {

    @TempDir
    Path tempDirectory;

    @Test
    void loadsLocalDotEnvWithoutOverridingEarlierCandidate() throws Exception {
        Path primary = tempDirectory.resolve(".env");
        Path secondary = tempDirectory.resolve("secondary.env");
        Files.writeString(primary, """
                # local model settings
                AI_ENABLED=true
                AI_API_KEY="secret-value"
                AI_MODEL=qwen-plus
                """);
        Files.writeString(secondary, "AI_MODEL=another-model\nINVALID LINE\n");

        Map<String, Object> result = ZhituApplication.loadDotEnv(List.of(primary, secondary));

        assertEquals("true", result.get("AI_ENABLED"));
        assertEquals("secret-value", result.get("AI_API_KEY"));
        assertEquals("qwen-plus", result.get("AI_MODEL"));
        assertFalse(result.containsKey("INVALID LINE"));
    }
}
