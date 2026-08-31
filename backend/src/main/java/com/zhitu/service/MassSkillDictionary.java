package com.zhitu.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 百万级 JD 使用的轻量技能词典。
 * 全量批处理不逐条调用大模型，避免百万次 API 调用带来的成本、速度和不可复现问题。
 * 低置信/重点岗位仍可在后续可信审核阶段使用大模型复核。
 */
@Component
public class MassSkillDictionary {

    public record SkillDef(String canonical, String stack, String category, List<String> aliases) {}
    public record SkillHit(String canonical, String stack, String category, String evidence, double confidence) {}

    private final List<SkillDef> skills = new ArrayList<>();

    public MassSkillDictionary() {
        add("Java", "后端开发", "编程语言", "java", "jdk");
        add("Python", "人工智能", "编程语言", "python", "python3");
        add("C++", "工程基础", "编程语言", "c++", "cpp");
        add("C", "工程基础", "编程语言", "c语言", "embedded c");
        add("C#", "工程基础", "编程语言", "c#", "csharp");
        add("Go", "后端开发", "编程语言", "golang", "go语言");
        add("Rust", "工程基础", "编程语言", "rust");
        add("JavaScript", "前端开发", "编程语言", "javascript", "js");
        add("TypeScript", "前端开发", "编程语言", "typescript", "ts");
        add("Scala", "大数据", "编程语言", "scala");
        add("Kotlin", "移动开发", "编程语言", "kotlin");
        add("SQL", "数据基础", "查询语言", "sql", "sql查询");

        add("Spring", "后端开发", "框架", "spring framework");
        add("Spring Boot", "后端开发", "框架", "springboot", "spring boot");
        add("Spring Cloud", "后端开发", "微服务", "springcloud", "spring cloud");
        add("MyBatis", "后端开发", "ORM", "mybatis", "mybatis-plus", "mybatis plus");
        add("Hibernate", "后端开发", "ORM", "hibernate", "jpa");
        add("Django", "后端开发", "框架", "django");
        add("Flask", "后端开发", "框架", "flask");
        add("FastAPI", "后端开发", "框架", "fastapi", "fast api");
        add("Node.js", "后端开发", "运行时", "node.js", "nodejs");
        add("Vue.js", "前端开发", "框架", "vue", "vue.js", "vue3");
        add("React", "前端开发", "框架", "react", "react.js", "reactjs");
        add("Angular", "前端开发", "框架", "angular");

        add("MySQL", "数据基础", "数据库", "mysql", "mysql数据库");
        add("PostgreSQL", "数据基础", "数据库", "postgresql", "postgres");
        add("Oracle", "数据基础", "数据库", "oracle");
        add("SQL Server", "数据基础", "数据库", "sql server", "mssql");
        add("Redis", "数据基础", "缓存", "redis", "redis缓存");
        add("MongoDB", "数据基础", "数据库", "mongodb", "mongo db");
        add("Elasticsearch", "数据基础", "搜索引擎", "elasticsearch", "elastic search", "es搜索");
        add("Neo4j", "人工智能", "图数据库", "neo4j");
        add("Milvus", "大模型应用", "向量数据库", "milvus");
        add("FAISS", "大模型应用", "向量数据库", "faiss");
        add("向量数据库", "大模型应用", "数据库", "vector database", "向量库", "pinecone", "chroma");

        add("Hadoop", "大数据", "离线计算", "hadoop", "hdfs", "mapreduce");
        add("Spark", "大数据", "计算引擎", "apache spark", "spark");
        add("Flink", "大数据", "实时计算", "apache flink", "flink");
        add("Kafka", "大数据", "消息队列", "apache kafka", "kafka");
        add("RocketMQ", "大数据", "消息队列", "rocketmq", "rocket mq");
        add("RabbitMQ", "后端开发", "消息队列", "rabbitmq", "rabbit mq");
        add("Hive", "大数据", "数据仓库", "hive");
        add("HBase", "大数据", "数据库", "hbase");
        add("ClickHouse", "大数据", "分析数据库", "clickhouse", "click house");
        add("Doris", "大数据", "分析数据库", "apache doris", "doris");
        add("StarRocks", "大数据", "分析数据库", "starrocks", "star rocks");
        add("数据仓库", "大数据", "数仓", "数仓", "data warehouse", "数据仓库");
        add("数据湖", "大数据", "湖仓", "data lake", "数据湖", "湖仓");
        add("ETL", "大数据", "数据集成", "etl", "数据集成", "数据清洗");
        add("数据治理", "大数据", "治理", "data governance", "数据治理");

        add("机器学习", "人工智能", "算法", "machine learning", "机器学习", "ml算法");
        add("深度学习", "人工智能", "算法", "deep learning", "深度学习");
        add("PyTorch", "人工智能", "框架", "pytorch", "torch");
        add("TensorFlow", "人工智能", "框架", "tensorflow");
        add("Scikit-learn", "人工智能", "框架", "scikit-learn", "sklearn");
        add("NLP", "人工智能", "自然语言处理", "nlp", "自然语言处理");
        add("计算机视觉", "智能系统", "视觉", "computer vision", "计算机视觉", "cv算法", "图像识别");
        add("目标检测", "智能系统", "视觉", "object detection", "目标检测", "yolo");
        add("推荐系统", "人工智能", "推荐", "recommendation system", "推荐系统");
        add("知识图谱", "人工智能", "知识工程", "knowledge graph", "知识图谱");

        add("大语言模型", "大模型应用", "基础模型", "llm", "large language model", "大语言模型", "大模型");
        add("Prompt Engineering", "大模型应用", "提示工程", "prompt engineering", "提示工程", "提示词工程", "prompt设计");
        add("RAG", "大模型应用", "检索增强", "rag", "retrieval augmented generation", "检索增强生成");
        add("LangChain", "大模型应用", "Agent框架", "langchain");
        add("LangGraph", "大模型应用", "Agent框架", "langgraph");
        add("AI Agent", "大模型应用", "智能体", "ai agent", "agent开发", "智能体开发", "智能体");
        add("MCP", "大模型应用", "协议", "model context protocol", "mcp", "模型上下文协议");
        add("Function Calling", "大模型应用", "工具调用", "function calling", "tool calling", "函数调用");
        add("Fine-tuning", "大模型应用", "模型训练", "fine-tuning", "finetune", "微调", "sft");
        add("RLHF", "大模型应用", "模型训练", "rlhf", "人类反馈强化学习");
        add("LoRA", "大模型应用", "模型训练", "lora", "qlora");

        add("Docker", "云原生", "容器", "docker", "容器化");
        add("Kubernetes", "云原生", "编排", "kubernetes", "k8s", "容器编排");
        add("Linux", "云原生", "操作系统", "linux", "centos", "ubuntu");
        add("Git", "工程基础", "版本控制", "git", "github", "gitlab");
        add("CI/CD", "云原生", "工程效能", "ci/cd", "cicd", "持续集成", "持续部署");
        add("Jenkins", "云原生", "工程效能", "jenkins");
        add("Nginx", "云原生", "网关", "nginx");
        add("微服务", "后端开发", "架构", "microservice", "微服务");
        add("分布式系统", "后端开发", "架构", "distributed system", "分布式系统", "分布式架构");
        add("RESTful API", "后端开发", "接口", "restful api", "rest api");

        add("AWS", "云计算", "云平台", "aws", "amazon web services");
        add("Azure", "云计算", "云平台", "azure");
        add("阿里云", "云计算", "云平台", "阿里云", "aliyun");
        add("华为云", "云计算", "云平台", "华为云");
        add("腾讯云", "云计算", "云平台", "腾讯云");

        add("MQTT", "物联网", "协议", "mqtt");
        add("Modbus", "物联网", "协议", "modbus");
        add("物联网", "物联网", "领域", "iot", "internet of things", "物联网");
        add("边缘计算", "物联网", "计算架构", "edge computing", "边缘计算");
        add("数字孪生", "智能系统", "仿真", "digital twin", "数字孪生");
        add("ROS", "智能系统", "机器人", "robot operating system", "ros", "ros2");
        add("PLC", "智能系统", "工业控制", "plc", "可编程逻辑控制器");
        add("AutoCAD", "机械设计", "CAD软件", "autocad", "auto cad");
        add("SolidWorks", "机械设计", "三维设计", "solidworks", "solid works");
        add("Creo", "机械设计", "三维设计", "creo", "pro/e", "proe");
        add("CATIA", "机械设计", "三维设计", "catia");
        add("NX", "机械设计", "三维设计", "ug nx", "siemens nx", "ug建模");
        add("Arduino", "嵌入式", "开发平台", "arduino");
        add("三维建模", "机械设计", "设计方法", "3d建模", "三维模型");
        add("机械制图", "机械设计", "工程制图", "工程制图", "二维工程图", "机械图纸");
        add("运动仿真", "机械设计", "仿真", "运动仿真分析");
    }

    private void add(String canonical, String stack, String category, String... aliases) {
        List<String> all = new ArrayList<>();
        all.add(canonical);
        all.addAll(List.of(aliases));
        skills.add(new SkillDef(canonical, stack, category, all));
    }

    public List<SkillHit> extract(String title, String description) {
        String text = normalize((title == null ? "" : title) + "\n" + (description == null ? "" : description));
        Set<String> seen = new LinkedHashSet<>();
        List<SkillHit> hits = new ArrayList<>();
        for (SkillDef def : skills) {
            String evidence = null;
            for (String alias : def.aliases()) {
                String needle = normalize(alias);
                if (needle.length() < 2 || (needle.length() <= 2 && needle.matches("[a-z0-9]+"))) continue;
                int index = text.indexOf(needle);
                if (index >= 0) {
                    int from = Math.max(0, index - 45);
                    int to = Math.min(text.length(), index + needle.length() + 65);
                    evidence = text.substring(from, to).trim();
                    break;
                }
            }
            if (evidence != null && seen.add(def.canonical())) {
                double confidence = requirementConfidence(text, evidence);
                hits.add(new SkillHit(def.canonical(), def.stack(), def.category(), evidence, confidence));
            }
        }
        return hits;
    }

    public String requirementType(String fullText, String evidence) {
        String window = normalize(evidence);
        if (containsAny(window, "优先", "加分", "preferred", "bonus", "有经验者优先")) return "PREFERRED";
        if (containsAny(window, "必须", "要求", "掌握", "精通", "熟练", "required", "must")) return "REQUIRED";
        return "MENTIONED";
    }

    private double requirementConfidence(String fullText, String evidence) {
        String window = normalize(evidence);
        if (containsAny(window, "必须", "精通", "熟练掌握", "required", "must")) return 0.94;
        if (containsAny(window, "要求", "掌握", "熟悉", "优先", "preferred")) return 0.86;
        return 0.74;
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(normalize(value))) return true;
        return false;
    }

    private String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    public Map<String, SkillDef> dictionary() {
        Map<String, SkillDef> result = new LinkedHashMap<>();
        for (SkillDef skill : skills) result.put(skill.canonical(), skill);
        return result;
    }
}
