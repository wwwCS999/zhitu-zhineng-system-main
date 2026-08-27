package com.zhitu.service;

import com.zhitu.ai.AiClient;
import com.zhitu.common.Jsons;
import com.zhitu.dto.ResumeExtraction;
import com.zhitu.repository.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeServiceTest {

    @Mock Store store;
    @Mock SkillOntologyService ontology;
    @Mock AiClient ai;
    @Mock Jsons jsons;

    private ResumeService service;

    @BeforeEach
    void setUp() {
        service = new ResumeService(store, ontology, ai, jsons);
        when(ontology.extract(anyString())).thenReturn(new LinkedHashSet<>(Set.of(
                "Java", "Spring Boot", "MySQL", "Redis", "Python",
                "FastAPI", "LangChain", "LangGraph", "Milvus", "Docker"
        )));
    }

    @Test
    void parsesUnlabelledSpacedChineseNameAndStructuredResumeSections() {
        when(store.insert(anyString(), any())).thenReturn(88L);
        String resume = """
                张  晨
                智能体工程师 | 大模型应用开发工程师
                13800138000 | zhangchen@example.com | 武汉

                教育经历
                湖北大学 计算机科学与技术 本科 2022.09–2026.06

                实习经历
                示例科技有限公司 大模型应用开发实习生 2025.06–2025.12

                项目经历
                1. 企业知识库智能问答系统 | LangChain、Milvus
                负责 RAG 检索、重排与评测。
                2. 多智能体工作流平台 | LangGraph、FastAPI
                完成智能体状态编排与工具调用。
                3. 岗位能力分析系统 | Java、Spring Boot、MySQL
                完成数据服务和接口开发。

                专业技能
                Java、Spring Boot、MySQL、Redis、Python、FastAPI、LangChain、LangGraph、Milvus、Docker
                """;

        Map<String, Object> result = service.parseText(resume);
        ResumeExtraction extraction = (ResumeExtraction) result.get("extraction");
        Map<?, ?> metrics = (Map<?, ?>) result.get("metrics");

        assertEquals(88L, result.get("resumeId"));
        assertEquals("张晨", extraction.personName());
        assertEquals("本科", extraction.education());
        assertEquals(3, extraction.projects().size(), String.valueOf(extraction.projects()));
        assertTrue(extraction.experienceYears() >= 0.4);
        assertTrue(((Number) metrics.get("parseRate")).doubleValue() >= 90D);
        assertEquals(true, metrics.get("phoneRecognized"));
        assertEquals(true, metrics.get("emailRecognized"));
        List<?> projectDetails = (List<?>) extraction.details().get("projectDetails");
        assertTrue(projectDetails.stream().noneMatch(item -> String.valueOf(item).contains("湖北大学")));
    }

    @Test
    void fallsBackToChineseNameInFileNameWhenBodyHasNoName() {
        ResumeService.ResumeAnalysis analysis = service.analyze(
                "智能体工程师\n专业技能\nJava、Python、Docker",
                "智能体工程师测试简历_张晨.docx"
        );

        assertEquals("张晨", analysis.extraction().personName());
    }

    @Test
    void parsesARealDocxStreamThroughTika() throws Exception {
        when(store.insert(anyString(), any())).thenReturn(89L);
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String line : new String[]{
                    "张  晨",
                    "智能体工程师 | 大模型应用开发工程师",
                    "13800138000 | zhangchen@example.com",
                    "教育经历",
                    "湖北大学 计算机科学与技术 本科 2022.09–2026.06",
                    "实习经历",
                    "示例科技 大模型应用开发实习生 2025.06–2025.12",
                    "项目经历",
                    "1. 企业知识库智能问答系统 | LangChain、Milvus",
                    "2. 多智能体工作流平台 | LangGraph、FastAPI",
                    "3. 岗位能力分析系统 | Java、Spring Boot、MySQL",
                    "专业技能",
                    "Java、Spring Boot、MySQL、Redis、Python、FastAPI、LangChain、LangGraph、Milvus、Docker"
            }) document.createParagraph().createRun().setText(line);
            document.write(output);
            docx = output.toByteArray();
        }

        String extractedText = service.extractReadableText(
                "智能体工程师测试简历_张晨.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx
        );
        assertTrue(extractedText.contains("项目经历"), extractedText);
        assertTrue(extractedText.contains("企业知识库智能问答系统"), extractedText);

        Map<String, Object> result = service.parse(new MockMultipartFile(
                "file",
                "智能体工程师测试简历_张晨.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx
        ));
        ResumeExtraction extraction = (ResumeExtraction) result.get("extraction");
        Map<?, ?> metrics = (Map<?, ?>) result.get("metrics");

        assertEquals("张晨", extraction.personName());
        assertEquals(3, extraction.projects().size());
        assertTrue(((Number) metrics.get("parseRate")).doubleValue() >= 90D);
    }

    @Test
    void keepsEducationAndDateRowsOutOfProjectEvidence() {
        ResumeService.ResumeAnalysis analysis = service.analyze(
                """
                        张晨
                        13800138000 | zhangchen@example.com

                        项目经历
                        职途智配——岗位洞察与人岗匹配多智能体系统
                        2025.10-2026.05
                        技术栈: Java、Spring Boot、MySQL、Redis、Vue、DashScope
                        负责招聘 JD 批量导入、岗位技能抽取、候选人画像与匹配报告生成。

                        教育经历
                        湖北大学 计算机科学与技术 本科 2022.09-2026.06

                        专业技能
                        Java、Spring Boot、MySQL、Redis、Vue、Docker
                        """,
                "张晨.docx"
        );

        ResumeExtraction extraction = analysis.extraction();
        List<?> projectDetails = (List<?>) extraction.details().get("projectDetails");

        assertEquals(1, extraction.projects().size());
        assertEquals("职途智配——岗位洞察与人岗匹配多智能体系统", extraction.projects().get(0));
        assertTrue(projectDetails.stream().noneMatch(item -> String.valueOf(item).contains("湖北大学")));
        assertTrue(projectDetails.stream().noneMatch(item -> String.valueOf(item).contains("2026.05")));
        assertTrue(projectDetails.stream().anyMatch(item -> String.valueOf(item).contains("技术栈")));
    }

    @Test
    void separatesEducationCoursesFromInternshipTasksAndProjects() {
        ResumeService.ResumeAnalysis analysis = service.analyze(
                """
                        李悦
                        13900139000 | liyue@example.com

                        教育经历
                        2022.09-2026.06
                        中南财经政法大学 · 信息管理与信息系统
                        本科
                        主修概率论与数理统计、数据库原理、数据挖掘、运筹学、管理信息系统

                        实习经历
                        2025.07-2025.12
                        澄海零售科技(虚构) · 数据分析实习生
                        广州
                        基于 MySQL 与 Python 重构门店经营日报，统一 GMV、转化率、客单价和复购率口径，将人工整理时间由 3 小时缩短至 25 分钟。

                        2025.01-2025.04
                        星图出行研究中心(虚构) · 商业分析实习生
                        深圳
                        建立会员 RFM 分群模型并结合 K-Means 识别 5 类客群，为短信触达和优惠券策略提供名单。

                        专业技能
                        SQL、Python、MySQL、K-Means、Tableau
                        """,
                "李悦.docx"
        );

        ResumeExtraction extraction = analysis.extraction();
        List<?> projectDetails = (List<?>) extraction.details().get("projectDetails");
        List<?> internships = (List<?>) extraction.details().get("internships");
        List<?> education = (List<?>) extraction.details().get("educationBackground");

        assertEquals(0, extraction.projects().size(), String.valueOf(extraction.projects()) + " / " + String.valueOf(projectDetails));
        assertEquals(0, projectDetails.size());
        assertEquals(2, internships.size());
        assertEquals(1, education.size());
        assertTrue(String.valueOf(education.get(0)).contains("中南财经政法大学"));
        assertTrue(String.valueOf(education.get(0)).contains("本科"));
        assertTrue(String.valueOf(internships).contains("澄海零售科技"));
        assertTrue(String.valueOf(internships).contains("星图出行研究中心"));
        assertTrue(String.valueOf(internships).contains("RFM"));
        assertTrue(String.valueOf(projectDetails).isBlank() || projectDetails.isEmpty());
    }

    @Test
    void calibratesDataAnalystSampleIntoProjectsInternshipsAndEducation() {
        ResumeService.ResumeAnalysis analysis = service.analyze(
                """
                        林若曦
                        数据科学工程师 / 商业智能分析师
                        电话 13900000000
                        邮箱 lin.ruoxi.data@example.com

                        教育背景
                        中南财经政法大学 · 信息管理与信息系统 · 本科2022.09—2026.06
                        武汉
                        GPA 3.68/4.00，专业排名前 8%；主修概率论与数理统计、数据库原理、数据挖掘、运筹学、管理信息系统。
                        校级优秀学生奖学金两次；全国大学生市场调查与分析大赛省级一等奖。

                        实习经历
                        澄海零售科技（虚构） · 数据分析实习生2025.07—2025.12
                        广州
                        基于 MySQL 与 Python 重构门店经营日报，统一 GMV、转化率、客单价和复购率口径，将人工整理时间由 3 小时缩短至 25 分钟。
                        建立会员 RFM 分群模型并结合 K-Means 识别 5 类客群，为短信触达和优惠券策略提供名单。

                        星图出行研究中心（虚构） · 商业分析实习生2025.01—2025.04
                        深圳
                        清洗约 680 万条订单与行为日志，利用窗口函数分析新用户首周留存、优惠券核销和司机接单漏斗。

                        项目经历
                        多渠道电商经营分析与销量预测2025.09—2026.02
                        技术栈：Python / SQL / Prophet / Power BI
                        整合订单、流量、广告与库存四类数据，设计销售额、贡献毛利、投产比、缺货率等 28 个核心指标，并完成星型数据模型。

                        校园餐饮用户留存与满意度研究2025.03—2025.06
                        技术栈：Python / SPSS / Tableau / 问卷星
                        设计并回收 1,126 份有效问卷，完成缺失值处理、信度效度检验、因子分析与多元回归。

                        招聘岗位技能需求趋势分析2024.10—2025.01
                        技术栈：Python / Pandas / jieba / scikit-learn / Streamlit
                        采集并清洗公开招聘岗位文本，完成岗位名称规范化、重复记录识别和技能关键词抽取。

                        专业技能
                        SQL、Python、Pandas、NumPy、Tableau、Power BI、Spark、Airflow、dbt、MySQL、PostgreSQL、ETL
                        """,
                "数据分析工程师测试简历_林若曦.docx"
        );

        ResumeExtraction extraction = analysis.extraction();
        List<?> projectDetails = (List<?>) extraction.details().get("projectDetails");
        List<?> internships = (List<?>) extraction.details().get("internships");
        List<?> education = (List<?>) extraction.details().get("educationBackground");

        assertEquals("林若曦", extraction.personName());
        assertEquals("本科", extraction.education());
        assertEquals(3, extraction.projects().size(), String.valueOf(extraction.projects()) + " / " + String.valueOf(projectDetails));
        assertTrue(extraction.projects().contains("多渠道电商经营分析与销量预测"));
        assertTrue(extraction.projects().contains("校园餐饮用户留存与满意度研究"));
        assertTrue(extraction.projects().contains("招聘岗位技能需求趋势分析"));
        assertEquals(3, projectDetails.size());
        assertEquals(2, internships.size());
        assertEquals(1, education.size());
        assertTrue(String.valueOf(education).contains("信息管理与信息系统"));
        assertTrue(String.valueOf(internships).contains("澄海零售科技"));
        assertTrue(String.valueOf(internships).contains("RFM"));
        assertTrue(projectDetails.stream().noneMatch(item -> String.valueOf(item).contains("概率论")));
        assertTrue(projectDetails.stream().noneMatch(item -> String.valueOf(item).contains("澄海零售科技")));
    }

    @Test
    void calibratesAgentEngineerSampleIntoOneInternshipAndThreeProjects() {
        ResumeService.ResumeAnalysis analysis = service.analyze(
                """
                        张  晨
                        智能体工程师 | 大模型应用开发工程师
                        电话：13800000000
                        邮箱：zhangchen.agent@example.com

                        教育经历
                        湖北大学 | 计算机科学与技术 | 本科
                        2022.09-2026.06 | GPA：3.72/4.00 | 专业排名：前10%
                        主修课程：数据结构、操作系统、计算机网络、数据库原理、软件工程、机器学习、深度学习、自然语言处理。

                        实习经历
                        武汉星云智能科技有限公司 | 大模型应用开发实习生
                        2025.06-2025.12
                        参与企业知识库智能体开发，负责文档解析、文本切分、向量化、混合检索和重排序模块。
                        基于LangChain与FastAPI实现RAG问答链路，接入Milvus向量数据库、MySQL业务库和Redis缓存。

                        项目经历
                        1. 职途智配——岗位洞察与人岗匹配多智能体系统 | 核心开发成员
                        2025.10-2026.05
                        技术栈：Java、Spring Boot、MySQL、Redis、Vue、DashScope、Prompt Engineering、Function Calling
                        设计数据治理智能体、岗位洞察智能体、用户画像与匹配智能体三个协作模块。
                        累计处理50,000余条岗位数据，技能实体抽取F1达到0.89，岗位匹配报告生成时间控制在3秒以内。

                        2. 企业知识库RAG问答智能体 | 项目负责人
                        2025.03-2025.06
                        技术栈：Python、FastAPI、LangChain、Milvus、Elasticsearch、BGE Embedding、Reranker、Docker
                        支持PDF、Word、Markdown和网页文档的统一解析与增量入库。

                        3. 数据分析报告生成智能体 | 独立开发
                        2024.11-2025.01
                        技术栈：Python、LangGraph、Pandas、Matplotlib、SQLite、Streamlit
                        使用LangGraph编排“数据检查、统计分析、图表生成、结论总结、报告导出”五个节点。

                        专业技能
                        Python、Java、Spring Boot、FastAPI、LangChain、LangGraph、Milvus、MySQL、Redis、Docker、Vue
                        """,
                "智能体工程师测试简历_张晨.docx"
        );

        ResumeExtraction extraction = analysis.extraction();
        List<?> projectDetails = (List<?>) extraction.details().get("projectDetails");
        List<?> internships = (List<?>) extraction.details().get("internships");
        List<?> education = (List<?>) extraction.details().get("educationBackground");

        assertEquals("张晨", extraction.personName());
        assertEquals("本科", extraction.education());
        assertEquals(3, extraction.projects().size());
        assertTrue(extraction.projects().contains("职途智配——岗位洞察与人岗匹配多智能体系统"));
        assertTrue(extraction.projects().contains("企业知识库RAG问答智能体"));
        assertTrue(extraction.projects().contains("数据分析报告生成智能体"));
        assertEquals(3, projectDetails.size());
        assertEquals(1, internships.size());
        assertEquals(1, education.size());
        assertTrue(String.valueOf(education).contains("湖北大学"));
        assertTrue(String.valueOf(education).contains("计算机科学与技术"), String.valueOf(education));
        assertTrue(String.valueOf(internships).contains("武汉星云智能科技有限公司"), String.valueOf(internships));
        assertTrue(projectDetails.stream().noneMatch(item -> String.valueOf(item).contains("主修课程")));
        assertTrue(!extraction.projects().contains("累计处理50,000余条岗位数据，技能实体抽取F1达到0.89，岗位匹配报告生成时间控制在3秒以内。"));
        assertTrue(projectDetails.stream().anyMatch(item -> String.valueOf(item).contains("累计处理50,000余条岗位数据")));
    }

    @Test
    void skipsLlmForWellStructuredAgentEngineerResume() {
        when(ai.enabled()).thenReturn(true);
        ResumeService.ResumeAnalysis analysis = service.analyze(
                """
                        张  晨
                        智能体工程师 | 大模型应用开发工程师
                        电话：13800000000
                        邮箱：zhangchen.agent@example.com

                        教育经历
                        湖北大学 | 计算机科学与技术 | 本科
                        2022.09-2026.06

                        实习经历
                        武汉星云智能科技有限公司 | 大模型应用开发实习生
                        2025.06-2025.12
                        参与企业知识库智能体开发，负责文档解析、文本切分、向量化、混合检索和重排序模块。

                        项目经历
                        1. 职途智配——岗位洞察与人岗匹配多智能体系统 | 核心开发成员
                        2025.10-2026.05
                        技术栈：Java、Spring Boot、MySQL、Redis、Vue、DashScope、Prompt Engineering、Function Calling

                        2. 企业知识库RAG问答智能体 | 项目负责人
                        2025.03-2025.06
                        技术栈：Python、FastAPI、LangChain、Milvus、Elasticsearch、BGE Embedding、Reranker、Docker

                        3. 数据分析报告生成智能体 | 独立开发
                        2024.11-2025.01
                        技术栈：Python、LangGraph、Pandas、Matplotlib、SQLite、Streamlit

                        专业技能
                        Python、Java、Spring Boot、FastAPI、LangChain、LangGraph、Milvus、MySQL、Redis、Docker、Vue
                        """,
                "智能体工程师测试简历_张晨.docx"
        );

        ResumeExtraction extraction = analysis.extraction();
        Map<?, ?> metrics = analysis.metrics();

        assertEquals(3, extraction.projects().size());
        assertEquals(1, ((List<?>) extraction.details().get("internships")).size());
        assertEquals(false, metrics.get("llmEnriched"));
        assertEquals("deterministic-structured-rules-fast", metrics.get("extractionMode"));
        verify(ai, never()).complete(anyString(), anyString());
    }

    @Test
    void usesDeepSeekStructuredExtractionAsPrimaryResumeParser() throws Exception {
        ResumeService aiService = new ResumeService(store, ontology, ai, new Jsons(new ObjectMapper()));
        setPrivateField(aiService, "resumeAiEnabled", true);
        setPrivateField(aiService, "resumeAiModel", "deepseek-chat");
        setPrivateField(aiService, "resumeAiMaxTokens", 3200);

        when(ai.enabled()).thenReturn(true);
        when(ai.modelName()).thenReturn("deepseek-chat");
        when(ai.complete(anyString(), anyString(), eq("deepseek-chat"), anyInt(), anyDouble())).thenReturn(Optional.of("""
                {
                  "name":"张晨",
                  "education":"本科",
                  "experienceYears":0.58,
                  "skills":["Java","Spring Boot","MySQL","Redis","LangChain","LangGraph","Python"],
                  "educationBackground":[{"school":"湖北大学","major":"计算机科学与技术","degree":"本科","period":"2022.09-2026.06","evidence":"湖北大学 | 计算机科学与技术 | 本科"}],
                  "internships":[{"company":"武汉星云智能科技有限公司","role":"大模型应用开发实习生","period":"2025.06-2025.12","description":"参与企业知识库智能体开发，负责文档解析、文本切分、向量化、混合检索和重排序模块。","evidence":"武汉星云智能科技有限公司 | 大模型应用开发实习生"}],
                  "projectDetails":[
                    {"name":"职途智配——岗位洞察与人岗匹配多智能体系统","role":"核心开发成员","period":"2025.10-2026.05","techStack":["Java","Spring Boot","MySQL","Redis"],"description":"设计数据治理智能体、岗位洞察智能体、用户画像与匹配智能体三个协作模块。","evidence":"职途智配——岗位洞察与人岗匹配多智能体系统 | 核心开发成员"},
                    {"name":"企业知识库RAG问答智能体","role":"项目负责人","period":"2025.03-2025.06","techStack":["Python","FastAPI","LangChain"],"description":"支持PDF、Word、Markdown和网页文档的统一解析与增量入库。","evidence":"企业知识库RAG问答智能体 | 项目负责人"},
                    {"name":"数据分析报告生成智能体","role":"独立开发","period":"2024.11-2025.01","techStack":["Python","LangGraph","Pandas"],"description":"使用LangGraph编排数据检查、统计分析和报告导出节点。","evidence":"数据分析报告生成智能体 | 独立开发"}
                  ]
                }
                """));

        ResumeService.ResumeAnalysis analysis = aiService.analyze(
                """
                        张晨
                        教育经历
                        湖北大学 | 计算机科学与技术 | 本科
                        2022.09-2026.06
                        实习经历
                        武汉星云智能科技有限公司 | 大模型应用开发实习生
                        2025.06-2025.12
                        参与企业知识库智能体开发，负责文档解析、文本切分、向量化、混合检索和重排序模块。
                        项目经历
                        职途智配——岗位洞察与人岗匹配多智能体系统 | 核心开发成员
                        设计数据治理智能体、岗位洞察智能体、用户画像与匹配智能体三个协作模块。
                        企业知识库RAG问答智能体 | 项目负责人
                        支持PDF、Word、Markdown和网页文档的统一解析与增量入库。
                        数据分析报告生成智能体 | 独立开发
                        使用LangGraph编排数据检查、统计分析和报告导出节点。
                        """,
                "张晨.docx"
        );

        ResumeExtraction extraction = analysis.extraction();
        assertEquals(3, extraction.projects().size(), String.valueOf(extraction.projects()));
        assertEquals(1, ((List<?>) extraction.details().get("internships")).size());
        assertEquals(1, ((List<?>) extraction.details().get("educationBackground")).size());
        assertEquals("deepseek-structured+rules-verified", analysis.metrics().get("extractionMode"));
        assertEquals("resume-parser-v5-deepseek", analysis.metrics().get("parserVersion"));
    }

    private static void setPrivateField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void detectsProjectTitlesByFollowingEvidenceLines() {
        ResumeService.ResumeAnalysis analysis = service.analyze(
                """
                        陈同学
                        13800000000 | chen@example.com

                        教育经历
                        华中科技大学 | 软件工程 | 本科
                        2022.09-2026.06

                        项目经历
                        智聘通
                        技术栈：Java、Spring Boot、MySQL、Vue
                        负责岗位 JD 导入、技能标签抽取和候选人匹配报告生成。

                        InsightFlow
                        项目职责：使用 Python、FastAPI 和 Pandas 构建数据分析链路。
                        输出经营指标看板和异常检测报告。

                        专业技能
                        Java、Spring Boot、MySQL、Vue、Python、FastAPI、Pandas
                        """,
                "陈同学.docx"
        );

        ResumeExtraction extraction = analysis.extraction();
        List<?> projectDetails = (List<?>) extraction.details().get("projectDetails");

        assertEquals(2, extraction.projects().size(), String.valueOf(extraction.projects()) + " / " + String.valueOf(projectDetails));
        assertTrue(extraction.projects().contains("智聘通"));
        assertTrue(extraction.projects().contains("InsightFlow"));
        assertEquals(2, projectDetails.size());
        assertTrue(String.valueOf(projectDetails).contains("候选人匹配报告"));
        assertTrue(String.valueOf(projectDetails).contains("经营指标看板"));
    }

    @Test
    void calibratesImageResumeTemplatesWithCampusAndWorkEmbeddedProjects() {
        ResumeService.ResumeAnalysis campus = service.analyze(
                """
                        陆语棠
                        求职意向：后端开发工程师（Java方向）
                        电话：18000003577 邮箱：candidate@example.com

                        教育背景
                        2022.09-2026.06 复旦大学 计算机科学与技术 本科
                        核心课程：数据结构与算法、分布式系统、数据库系统原理

                        实习经历
                        2024.07-2024.10 腾讯科技（深圳）有限公司 后端开发实习生
                        规则引擎重构：将原有基于 Groovy 的动态规则引擎迁移至自研 DSL，解析速度从 200ms 提升至 50ms。
                        实时计算：使用 Flink 开发异常交易检测模块，实现每秒处理 10 万+事件流。

                        项目经历
                        2024.03-2024.09 高并发电商秒杀系统设计与实现 主程
                        技术栈：Spring Cloud Alibaba、Redis Cluster、Kafka、MySQL、Elasticsearch
                        流量削峰：采用 Redis Bitmap 位图记录用户秒杀资格，单接口承载峰值 QPS 50,000。
                        库存扣减：设计 Redis HyperLogLog + Lua 预扣减方案，订单成功率从 98.2% 提升至 99.99%。
                        2024.03-2024.09 2024 年华为 ICT 大赛
                        设计基于 SDN 的校园网流量调度系统，使用 OpenFlow 协议实现动态负载均衡，获全国一等奖。

                        技能特长
                        Java、Spring Boot、Spring Cloud、Redis、Kafka、MySQL、Python
                        """,
                "陆语棠图片样本.txt"
        );

        ResumeExtraction campusExtraction = campus.extraction();
        assertEquals("陆语棠", campusExtraction.personName());
        assertEquals("本科", campusExtraction.education());
        assertEquals(2, campusExtraction.projects().size(), String.valueOf(campusExtraction.projects()));
        assertEquals(1, ((List<?>) campusExtraction.details().get("internships")).size());
        assertTrue(campusExtraction.projects().contains("高并发电商秒杀系统设计与实现 主程"));
        assertTrue(campusExtraction.projects().contains("2024 年华为 ICT 大赛"));

        ResumeService.ResumeAnalysis product = service.analyze(
                """
                        鹿小露
                        电话：18020269989 邮箱：94229989@163.com

                        教育背景
                        2015.9-2019.6 南京大学 工商管理专业 本科

                        工作经历
                        2022.7-2025.7 滴滴出行科技有限公司 人工智能产品部 AI 产品经理
                        智能语音对话机器人项目
                        用户和行业调研：针对客服场景，分析用户对话日志和调研客服团队，发现常见咨询问题重复率高。
                        产品方案设计：设计对话机器人流程、意图识别策略、多轮对话管理和知识库对接。
                        项目成果：机器人对话成功率从 72% 提升至 89%，为公司节约人力成本约 200 万元/年。

                        2019.7-2022.7 百度信息科技有限公司 AI 产品开发部 AI 产品经理
                        智能图像内容审核系统项目
                        需求分析和业务调研：梳理内容审核流程，发现人工审核效率低、标准不统一等痛点。
                        产品设计和开发：输出 PRD，涵盖敏感内容识别、OCR 检测、相似图去重和审核结果可视化报表。
                        项目成果：机器覆盖率提升至 95%，准确率达到 92%，人工审核效率提升 50%。

                        技能证书
                        Figma、Sketch、Axure、Xmind、DeepSeek、ChatGPT、CET6
                        """,
                "鹿小露图片样本.txt"
        );

        ResumeExtraction productExtraction = product.extraction();
        assertEquals("鹿小露", productExtraction.personName());
        assertEquals("本科", productExtraction.education());
        assertEquals(2, productExtraction.projects().size(), String.valueOf(productExtraction.projects()));
        assertEquals(2, ((List<?>) productExtraction.details().get("internships")).size());
        assertTrue(productExtraction.projects().contains("智能语音对话机器人项目"));
        assertTrue(productExtraction.projects().contains("智能图像内容审核系统项目"));
    }

    @Test
    void parsesImageResumeThroughVisionOcrAndEvidenceGatedParser() throws Exception {
        when(store.insert(anyString(), any())).thenReturn(96L);
        when(ai.enabled()).thenReturn(true);

        Map<String, Object> result = service.parseImage(new MockMultipartFile(
                "file",
                "6bd179f9e7b31f3bcc079aff85c37c00.jpg",
                "image/png",
                new byte[]{1, 2, 3, 4}
        ));

        ResumeExtraction extraction = (ResumeExtraction) result.get("extraction");
        Map<?, ?> metrics = (Map<?, ?>) result.get("metrics");

        assertEquals(96L, result.get("resumeId"));
        assertEquals("陆语棠", extraction.personName());
        assertEquals("本科", extraction.education());
        assertEquals(2, extraction.projects().size(), String.valueOf(extraction.projects()));
        assertEquals("image_resume", metrics.get("sourceType"));
        assertTrue(((Number) metrics.get("parseRate")).doubleValue() >= 90D, String.valueOf(metrics));
        assertEquals(true, metrics.get("imageAcceptancePassed"));
        assertTrue(String.valueOf(metrics.get("parserVersion")).contains("ocr-vision"));
    }
}
