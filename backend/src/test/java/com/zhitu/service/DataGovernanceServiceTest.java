package com.zhitu.service;

import com.zhitu.repository.Store;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataGovernanceServiceTest {

    @Mock Store store;

    private DataGovernanceService service;

    @BeforeEach
    void setUp() {
        service = new DataGovernanceService(store);
        when(store.maybe(anyString(), any())).thenReturn(Optional.empty());
        when(store.list(anyString(), any())).thenReturn(List.of());
        when(store.insert(anyString(), any())).thenReturn(42L);
    }

    @Test
    void returnsExtractedWordTextForImmediateJobParsing() throws Exception {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("机器视觉算法工程师");
            document.createParagraph().createRun().setText("负责工业视觉检测系统开发，要求掌握 Python、OpenCV 与深度学习。");
            document.write(output);
            docx = output.toByteArray();
        }

        Map<String, Object> result = service.upload(new MockMultipartFile(
                "file",
                "02_机器视觉算法工程师.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx
        ), "REPORT");

        assertEquals(42L, result.get("documentId"));
        assertEquals("02_机器视觉算法工程师.docx", result.get("sourceName"));
        assertTrue(String.valueOf(result.get("extractedText")).contains("机器视觉算法工程师"));
        assertTrue(String.valueOf(result.get("extractedText")).contains("OpenCV"));
        assertTrue(((Number) result.get("extractedCharacters")).intValue() > 20);
        assertFalse((Boolean) result.get("textTruncated"));
    }
}
