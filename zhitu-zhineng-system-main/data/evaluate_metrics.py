from pathlib import Path
import csv
import json
import re

ROOT = Path(__file__).resolve().parent

SKILL_ALIASES = {
    "Java": ["java", "jdk", "java开发"],
    "Spring Boot": ["spring boot", "springboot"],
    "Spring Cloud": ["spring cloud", "springcloud"],
    "MySQL": ["mysql", "mysql数据库"],
    "Redis": ["redis", "redis缓存"],
    "Kafka": ["kafka", "apache kafka", "消息队列"],
    "Python": ["python", "python编程"],
    "机器学习": ["机器学习", "machine learning", "ml"],
    "深度学习": ["深度学习", "deep learning", "dl"],
    "PyTorch": ["pytorch", "torch"],
    "TensorFlow": ["tensorflow", "tf"],
    "大模型API调用": ["大模型api调用", "llm api", "模型api", "openai api"],
    "Prompt Engineering": ["prompt engineering", "prompt设计", "提示工程"],
    "RAG": ["rag", "检索增强生成", "retrieval augmented generation"],
    "LangChain": ["langchain", "langchain框架"],
    "LangGraph": ["langgraph", "langgraph框架"],
    "AI Agent": ["ai agent", "agent开发", "智能体开发"],
    "MCP": ["mcp", "model context protocol", "模型上下文协议"],
    "向量数据库": ["向量数据库", "milvus", "faiss", "chroma", "pinecone"],
    "知识图谱": ["知识图谱", "knowledge graph", "neo4j"],
    "Hadoop": ["hadoop", "hdfs", "mapreduce"],
    "Spark": ["spark", "apache spark"],
    "Flink": ["flink", "apache flink"],
    "数据仓库": ["数据仓库", "数仓", "data warehouse"],
    "数据治理": ["数据治理", "data governance"],
    "ETL": ["etl", "数据清洗", "数据集成"],
    "Pandas": ["pandas"],
    "NumPy": ["numpy"],
    "Matplotlib": ["matplotlib"],
    "Seaborn": ["seaborn"],
    "Tableau": ["tableau"],
    "Power BI": ["power bi", "powerbi"],
    "Airflow": ["airflow", "apache airflow"],
    "dbt": ["dbt", "data build tool"],
    "A/B测试": ["a/b测试", "ab测试", "a/b test", "ab test"],
    "时间序列分析": ["时间序列分析", "时间序列", "time series"],
    "用户分群": ["用户分群", "人群分层"],
    "漏斗分析": ["漏斗分析"],
    "RFM": ["rfm"],
    "K-Means": ["k-means", "kmeans"],
    "XGBoost": ["xgboost"],
    "Prophet": ["prophet"],
    "特征工程": ["特征工程"],
    "交叉验证": ["交叉验证", "cross validation"],
    "SPSS": ["spss"],
    "统计推断": ["统计推断"],
    "假设检验": ["假设检验", "hypothesis testing"],
    "回归分析": ["回归分析"],
    "数据可视化": ["数据可视化", "可视化分析"],
    "指标体系": ["指标体系", "指标设计"],
    "同期群分析": ["同期群分析", "cohort analysis"],
    "经营分析": ["经营分析"],
    "实验设计": ["实验设计"],
    "Docker": ["docker", "容器化"],
    "Kubernetes": ["kubernetes", "k8s", "容器编排"],
    "Linux": ["linux"],
    "Git": ["git", "github", "gitlab"],
    "CI/CD": ["ci/cd", "持续集成", "持续部署"],
    "物联网协议": ["物联网协议", "mqtt", "modbus", "coap"],
    "嵌入式C": ["嵌入式c", "c语言", "embedded c"],
    "边缘计算": ["边缘计算", "edge computing"],
    "数字孪生": ["数字孪生", "digital twin"],
    "计算机视觉": ["计算机视觉", "图像识别", "cv"],
    "ROS": ["ros", "robot operating system"],
    "RESTful API": ["restful api", "rest api", "接口开发"],
    "微服务": ["微服务", "microservice"],
    "分布式系统": ["分布式系统", "分布式架构"],
    "单元测试": ["单元测试", "junit", "pytest"],
    "SQL": ["sql", "sql查询", "sql语句", "sql开发"],
    "JavaScript": ["javascript", "js"],
    "TypeScript": ["typescript", "ts"],
    "Vue.js": ["vue.js", "vuejs", "vue3", "vue"],
    "React": ["react", "reactjs"],
    "Node.js": ["node.js", "nodejs"],
    "Angular": ["angular"],
    "C++": ["c++", "cpp"],
    "C": ["c语言"],
    "C#": ["c#", "csharp"],
    "Go": ["go语言", "golang"],
    "Rust": ["rust"],
    "Scala": ["scala"],
    "PostgreSQL": ["postgresql", "postgres"],
    "MongoDB": ["mongodb"],
    "Elasticsearch": ["elasticsearch", "es搜索"],
    "Hive": ["hive"],
    "HBase": ["hbase"],
    "ClickHouse": ["clickhouse"],
    "Nginx": ["nginx"],
    "Jenkins": ["jenkins"],
    "AWS": ["aws", "amazonwebservices"],
    "Azure": ["azure"],
    "阿里云": ["阿里云", "aliyun"],
    "Scikit-learn": ["scikit-learn", "sklearn"],
}


def norm(text: str) -> str:
    return re.sub(r"[\s_\-·/]+", "", (text or "").lower())


def extract_skills(text: str) -> set[str]:
    normalized = norm(text)
    result = set()
    for canonical, aliases in SKILL_ALIASES.items():
        if any(norm(alias) in normalized for alias in aliases):
            result.add(canonical)
    if "Java" in result and "java" not in normalized.replace("javascript", ""):
        result.remove("Java")
    if "SQL" in result:
        stripped = normalized.replace("mysql", "").replace("postgresql", "").replace("nosql", "")
        if "sql" not in stripped:
            result.remove("SQL")
    return result


def prf(tp: int, fp: int, fn: int) -> tuple[float, float, float]:
    precision = tp / (tp + fp) if tp + fp else 0
    recall = tp / (tp + fn) if tp + fn else 0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0
    return precision, recall, f1


def main() -> None:
    tests = json.loads((ROOT / "test-cases.json").read_text(encoding="utf-8"))
    jd_gold = json.loads((ROOT / "gold-extractions.json").read_text(encoding="utf-8"))
    with (ROOT / "sample-jd-120.csv").open(encoding="utf-8-sig") as f:
        jd_rows = list(csv.DictReader(f))

    assert len(jd_rows) >= 100, "JD 测试集必须不少于 100 条"
    assert len(jd_gold) >= 100, "JD 金标集必须不少于 100 条"

    tp = fp = fn = 0
    for row, gold in zip(jd_rows, jd_gold):
        predicted = extract_skills(row["招聘岗位"] + "\n" + row["职位描述"])
        truth = set(gold["requiredSkills"])
        tp += len(predicted & truth)
        fp += len(predicted - truth)
        fn += len(truth - predicted)
    p, r, f1 = prf(tp, fp, fn)

    resume_tp = resume_fp = resume_fn = 0
    for case in tests.get("resumeCases", []):
        predicted = extract_skills(case["text"])
        truth = set(case["skills"])
        resume_tp += len(predicted & truth)
        resume_fp += len(predicted - truth)
        resume_fn += len(truth - predicted)
    rp, rr, rf1 = prf(resume_tp, resume_fp, resume_fn)

    result = {
        "jd_parse_precision": round(p, 4),
        "jd_parse_recall": round(r, 4),
        "jd_parse_f1": round(f1, 4),
        "resume_skill_precision": round(rp, 4),
        "resume_skill_recall": round(rr, 4),
        "resume_skill_f1": round(rf1, 4),
        "jd_count": len(jd_rows),
        "target": "JD 解析准确率 / F1 >= 0.90",
        "hallucination_control": "实时解析采用原文证据门禁，未被 JD 原文或别名库支持的模型候选不入库。"
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    (ROOT / "evaluation-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
