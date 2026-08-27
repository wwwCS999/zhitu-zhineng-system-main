package com.zhitu.bootstrap;

import com.zhitu.repository.Store;
import com.zhitu.service.DataGovernanceService;
import com.zhitu.service.JobInsightService;
import com.zhitu.service.LearningPlanningService;
import com.zhitu.service.MatchingService;
import com.zhitu.service.ResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;

/**
 * H2 演示数据初始化。
 * 百万 MySQL 的探新/演化/回测不允许在应用启动阶段自动执行，
 * 否则治理未完成时会阻断 Spring Boot 启动。
 */
@Component
public class DemoDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final Store store;
    private final DataGovernanceService governance;
    private final JobInsightService insight;
    private final ResumeService resumes;
    private final MatchingService matching;
    private final LearningPlanningService learning;

    @Value("${app.data-file:../data/sample-jd-120.csv}")
    private String dataFile;

    public DemoDataInitializer(
            Store store,
            DataGovernanceService governance,
            JobInsightService insight,
            ResumeService resumes,
            MatchingService matching,
            LearningPlanningService learning
    ) {
        this.store = store;
        this.governance = governance;
        this.insight = insight;
        this.resumes = resumes;
        this.matching = matching;
        this.learning = learning;
    }

    @Override
    public void run(String... args) throws Exception {
        if (store.count("job_posting") > 0) {
            log.info("H2 演示数据已存在，跳过初始化");
            return;
        }

        Path path = Path.of(dataFile);
        if (Files.exists(path)) {
            governance.importCsv(path);
        } else {
            ClassPathResource resource = new ClassPathResource("sample-jd-120.csv");
            Path temp = Files.createTempFile("sample-jd-", ".csv");
            Files.copy(resource.getInputStream(), temp, StandardCopyOption.REPLACE_EXISTING);
            governance.importCsv(temp);
        }

        try {
            insight.parseAll();
        } catch (Exception e) {
            log.warn("演示 JD 初始化解析失败，但不阻止系统启动：{}", e.getMessage());
        }

        try {
            String text = "姓名：张同学\n本科，2年 Java 开发经验。掌握 Java、Spring Boot、MySQL、Redis、Git、Docker、RESTful API。" +
                    "项目：负责企业知识库问答系统，使用大模型API调用与向量数据库完成检索服务。";
            Map<String, Object> resume = resumes.parseText(text);
            long resumeId = ((Number) resume.get("resumeId")).longValue();
            Optional<Map<String, Object>> role = store.maybe(
                    "SELECT id FROM job_role WHERE role_name LIKE '%Java%' ORDER BY id LIMIT 1",
                    Map.of()
            );
            if (role.isEmpty()) {
                role = store.maybe("SELECT id FROM job_role ORDER BY id LIMIT 1", Map.of());
            }
            if (role.isPresent()) {
                long roleId = ((Number) role.get().get("id")).longValue();
                Map<String, Object> match = matching.analyze(resumeId, roleId);
                learning.generate(((Number) match.get("id")).longValue(), 12, 8);
            }
        } catch (Exception e) {
            log.warn("演示简历/匹配初始化失败，但不阻止系统启动：{}", e.getMessage());
        }

        log.info("演示数据初始化完成；百万探新、回测、演化由前端显式触发");
    }
}
