#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 10000+ 条「新一代信息技术领域」岗位 JD 数据（CSV，21 列，中文表头）。

设计要点（对齐赛题 XH-202621）：
  1. 跨 2020–2026 共 7 个年份 —— 支撑「新岗位发现 + 既有岗位能力动态演化」。
  2. 限定新一代信息技术领域：大模型应用 / 人工智能 / 大数据 / 智能系统 / 物联网 / 云原生 / 后端开发。
  3. 职位描述内含具体技能点 —— 支撑技能抽取与全景图谱。
  4. 新岗位只在其「萌芽年份」之后出现（如 AI Agent 工程师 2024 年起）—— 支撑新岗位发现。
  5. 既有岗位技能按时代演化（如 Java：Spring MVC → Spring Boot/微服务 → AI 工具链）。
  6. 埋入脏数据：约 3% 重复(抄袭)、约 2% 噪音(缺字段)、少量时滞(旧技能/旧日期) —— 支撑治理智能体。

输出：../data/generated-jobs-10000.csv（UTF-8 with BOM，Excel 可直接打开）
用法：python scripts/generate_job_data.py [目标条数] [随机种子]
"""
import csv
import random
import datetime
import os
import sys

# ============================ 基础词表 ============================
COMPANIES = [
    "星河科技", "云启智能", "智联未来", "数据引擎科技", "云端智算", "慧联科技", "芯智科技",
    "蓝鲸软件", "数海信息", "智源研究院", "灵犀智能", "天工科技", "华云数据", "视界科技",
    "边缘智能", "算力网络", "微步科技", "拓扑智能", "智臻信息", "光年科技", "认知智能",
    "深蓝科技", "矩阵智能", "星环科技", "睿思智能", "链云科技", "伏羲智能", "青桐科技",
    "苍穹数据", "智航科技", "量子智能", "启明信息", "凌云科技", "极光数据", "磐石软件",
    "蜂巢智能", "数澜科技", "亿智信息", "天枢科技", "经纬智能", "峰云数据", "源生科技",
]

CITIES = [
    ("北京", "北京海淀区", "北京中关村"),
    ("北京", "北京朝阳区", "北京望京"),
    ("上海", "上海浦东新区", "上海张江"),
    ("上海", "上海徐汇区", "上海漕河泾"),
    ("深圳", "深圳南山区", "深圳科技园"),
    ("深圳", "深圳福田区", "深圳华强北"),
    ("广州", "广州天河区", "广州科学城"),
    ("杭州", "杭州西湖区", "杭州未来科技城"),
    ("杭州", "杭州余杭区", "杭州梦想小镇"),
    ("成都", "成都高新区", "成都天府软件园"),
    ("南京", "南京雨花台区", "南京软件谷"),
    ("武汉", "武汉洪山区", "武汉光谷"),
    ("西安", "西安雁塔区", "西安高新区"),
    ("苏州", "苏州工业园区", "苏州独墅湖"),
    ("合肥", "合肥蜀山区", "合肥高新区"),
    ("长沙", "长沙岳麓区", "长沙高新区"),
    ("重庆", "重庆渝北区", "重庆两江新区"),
    ("厦门", "厦门思明区", "厦门软件园"),
]

PLATFORMS = ["BOSS直聘", "企业官网", "智联招聘", "前程无忧", "高校就业网", "猎聘", "拉勾网"]
EDUCATION = ["大专及以上", "本科及以上", "硕士及以上", "博士"]
EXPERIENCE = ["应届生", "1年以上", "3年以上", "5年以上", "8年以上"]
RECRUIT_TYPE = ["社会招聘", "社会招聘", "社会招聘", "校园招聘", "实习"]

# 随机项目亮点（让描述产生差异，避免全部雷同；同时保留约 3% 作为真实“抄袭”重复）
PROJECT_PHRASES = [
    "参与公司核心业务中台建设", "负责重点客户项目交付", "参与新一代产品从 0 到 1 研发",
    "负责高并发场景下的性能攻坚", "参与技术体系升级与重构", "负责数据资产化与指标体系建设",
    "参与端云协同与边缘计算落地", "负责大模型业务场景的落地验证", "参与多团队协同的复杂项目",
    "负责关键系统的稳定性与可观测性建设", "参与行业解决方案的标准化输出", "负责安全合规体系的技术落地",
    "参与 AI 能力的工程化封装与平台化", "负责业务指标的增长与量化复盘", "参与开源项目的贡献与社区协作",
    "负责算法模型的工程化部署与迭代", "参与跨部门数据治理专项", "负责多云环境下的架构治理",
    "参与低代码与智能化研发工具建设", "负责核心链路的压测与容量规划",
]

# ============================ 岗位目录 ============================
# skills 按时代演化；emerging_year 为新岗位首次出现年份（None 表示一直存在）
# salary 为 (最低月薪, 最高月薪) 千元；base 用于随年份通胀
JOBS = {
    # ---- 后端开发 ----
    "Java 开发工程师": {
        "category": "后端开发", "emerging_year": None,
        "salary": (10, 18), "education": "本科及以上", "experience": "3年以上",
        "duties": "负责企业级后端服务与核心业务系统的设计、开发与优化，参与高并发网关、交易链路与数据一致性治理。",
        "skills": {
            "2020-2021": ["Java", "Spring MVC", "MyBatis", "MySQL", "Redis", "Tomcat", "单体架构"],
            "2022-2023": ["Java", "Spring Boot", "微服务", "MySQL", "Redis", "Kafka", "Docker", "Nacos"],
            "2024-2026": ["Java", "Spring Boot", "微服务", "Kubernetes", "CI/CD", "AI 辅助编程", "LangChain", "可观测性"],
        },
    },
    "前端开发工程师": {
        "category": "后端开发", "emerging_year": None,
        "salary": (9, 16), "education": "本科及以上", "experience": "2年以上",
        "duties": "负责 Web 与移动端界面的开发、性能优化与工程化建设，保障多端一致性与可维护性。",
        "skills": {
            "2020-2021": ["JavaScript", "Vue2", "jQuery", "Webpack", "CSS"],
            "2022-2023": ["TypeScript", "Vue3", "React", "Vite", "微前端", "Node.js"],
            "2024-2026": ["TypeScript", "Vue3", "React", "微前端", "低代码", "AI 编程助手", "WebAssembly"],
        },
    },
    "软件测试工程师": {
        "category": "后端开发", "emerging_year": None,
        "salary": (8, 14), "education": "本科及以上", "experience": "1年以上",
        "duties": "负责功能、性能与自动化测试体系搭建，保障交付质量与回归效率。",
        "skills": {
            "2020-2021": ["功能测试", "Postman", "JMeter", "Bug 管理", "SQL"],
            "2022-2023": ["接口自动化", "Python", "Selenium", "CI/CD", "性能测试"],
            "2024-2026": ["测试左移", "Python", "Playwright", "全链路压测", "AI 测试", "混沌工程"],
        },
    },
    # ---- 大数据 ----
    "大数据开发工程师": {
        "category": "大数据", "emerging_year": None,
        "salary": (12, 20), "education": "本科及以上", "experience": "3年以上",
        "duties": "负责离线与实时数据仓库建设、数据管道开发与调度，支撑业务指标与数据产品。",
        "skills": {
            "2020-2021": ["Hadoop", "Hive", "MapReduce", "HBase", "SQL", "Sqoop"],
            "2022-2023": ["Spark", "Flink", "Hive", "Kafka", "ClickHouse", "数据湖"],
            "2024-2026": ["Spark", "Flink", "数据湖", "Iceberg", "实时数仓", "DataOps", "大模型数据工程"],
        },
    },
    "数据治理工程师": {
        "category": "大数据", "emerging_year": 2022,
        "salary": (10, 17), "education": "本科及以上", "experience": "2年以上",
        "duties": "负责数据标准、质量、血缘与元数据治理，保障数据资产可信可用。",
        "skills": {
            "2020-2021": ["数据标准", "数据质量", "元数据", "ETL", "SQL"],
            "2022-2023": ["数据血缘", "数据质量", "数据资产", "DataHub", "SQL"],
            "2024-2026": ["数据血缘", "数据资产", "DataOps", "数据可信", "AI 数据治理", "隐私计算"],
        },
    },
    "数据库管理员": {
        "category": "大数据", "emerging_year": None,
        "salary": (11, 18), "education": "本科及以上", "experience": "3年以上",
        "duties": "负责数据库高可用架构、性能调优、备份恢复与容量规划。",
        "skills": {
            "2020-2021": ["MySQL", "Oracle", "主从复制", "备份恢复", "性能调优"],
            "2022-2023": ["MySQL", "TiDB", "Redis", "高可用", "性能调优", "自动化运维"],
            "2024-2026": ["TiDB", "OceanBase", "云数据库", "自动化运维", "AIOps", "向量数据库"],
        },
    },
    # ---- 人工智能 ----
    "机器学习工程师": {
        "category": "人工智能", "emerging_year": None,
        "salary": (14, 24), "education": "硕士及以上", "experience": "2年以上",
        "duties": "负责机器学习模型的训练、评估与上线，构建推荐、风控或预测类算法能力。",
        "skills": {
            "2020-2021": ["Python", "Scikit-learn", "XGBoost", "特征工程", "TensorFlow"],
            "2022-2023": ["Python", "深度学习", "PyTorch", "TensorFlow", "特征工程", "模型部署"],
            "2024-2026": ["Python", "PyTorch", "大模型微调", "LoRA", "模型压缩", "RAG", "MLOps"],
        },
    },
    "计算机视觉算法工程师": {
        "category": "人工智能", "emerging_year": None,
        "salary": (15, 25), "education": "硕士及以上", "experience": "2年以上",
        "duties": "负责图像识别、目标检测、分割等视觉算法的研发与落地。",
        "skills": {
            "2020-2021": ["OpenCV", "传统图像处理", "C++", "分类网络", "TensorFlow"],
            "2022-2023": ["PyTorch", "目标检测", "图像分割", "C++", "模型部署", "TensorRT"],
            "2024-2026": ["PyTorch", "多模态", "视觉大模型", "SAM", "TensorRT", "边缘推理", "3D 视觉"],
        },
    },
    "自然语言处理算法工程师": {
        "category": "人工智能", "emerging_year": None,
        "salary": (15, 25), "education": "硕士及以上", "experience": "2年以上",
        "duties": "负责文本理解、信息抽取、对话与生成等 NLP 算法的研发。",
        "skills": {
            "2020-2021": ["分词", "词向量", "LSTM", "CRF", "信息抽取", "TensorFlow"],
            "2022-2023": ["BERT", "预训练模型", "文本分类", "信息抽取", "PyTorch"],
            "2024-2026": ["大模型", "Prompt Engineering", "微调", "RAG", "多模态", "智能体"],
        },
    },
    # ---- 智能系统 ----
    "智能系统工程师": {
        "category": "智能系统", "emerging_year": 2022,
        "salary": (13, 22), "education": "本科及以上", "experience": "3年以上",
        "duties": "负责智能硬件与系统软件的设计、集成与优化，推动端侧智能化落地。",
        "skills": {
            "2020-2021": ["嵌入式 Linux", "C/C++", "驱动开发", "RTOS", "硬件调试"],
            "2022-2023": ["边缘计算", "C/C++", "Linux", "模型部署", "传感器融合"],
            "2024-2026": ["边缘智能", "端侧大模型", "NPU 推理", "模型量化", "机器人 OS", "AIoT"],
        },
    },
    "嵌入式开发工程师": {
        "category": "智能系统", "emerging_year": None,
        "salary": (12, 20), "education": "本科及以上", "experience": "2年以上",
        "duties": "负责嵌入式固件开发、驱动移植与性能优化。",
        "skills": {
            "2020-2021": ["C", "STM32", "RTOS", "UART/SPI/I2C", "驱动开发"],
            "2022-2023": ["C/C++", "FreeRTOS", "ARM", "低功耗", "通信协议"],
            "2024-2026": ["C/C++", "RISC-V", "边缘 AI", "模型量化", "AIoT", "安全启动"],
        },
    },
    "网络安全工程师": {
        "category": "智能系统", "emerging_year": None,
        "salary": (13, 22), "education": "本科及以上", "experience": "3年以上",
        "duties": "负责安全防护体系、漏洞挖掘与应急响应，保障系统与数据安全。",
        "skills": {
            "2020-2021": ["防火墙", "入侵检测", "漏洞扫描", "等保", "渗透测试"],
            "2022-2023": ["渗透测试", "零信任", "安全运营", "威胁情报", "合规"],
            "2024-2026": ["零信任", "AI 安全", "大模型安全", "数据安全", "供应链安全", "安全自动化"],
        },
    },
    # ---- 物联网 ----
    "物联网平台工程师": {
        "category": "物联网", "emerging_year": None,
        "salary": (12, 20), "education": "本科及以上", "experience": "2年以上",
        "duties": "负责物联网平台接入、设备管理与数据采集层的设计与开发。",
        "skills": {
            "2020-2021": ["MQTT", "TCP/IP", "设备接入", "传感器", "云端通信"],
            "2022-2023": ["MQTT", "边缘网关", "设备管理", "时序数据库", "云端通信"],
            "2024-2026": ["AIoT", "边缘智能", "数字孪生", "时序数据库", "5G", "云边协同"],
        },
    },
    # ---- 云原生 ----
    "云原生开发工程师": {
        "category": "云原生", "emerging_year": 2021,
        "salary": (13, 22), "education": "本科及以上", "experience": "2年以上",
        "duties": "负责容器化、微服务与云原生基础设施的建设与优化。",
        "skills": {
            "2020-2021": ["Docker", "Kubernetes", "微服务", "CI/CD", "Linux"],
            "2022-2023": ["Kubernetes", "服务网格", "微服务", "CI/CD", "可观测性"],
            "2024-2026": ["Kubernetes", "服务网格", "Serverless", "可观测性", "FinOps", "GitOps"],
        },
    },
    "DevOps 工程师": {
        "category": "云原生", "emerging_year": 2021,
        "salary": (12, 20), "education": "本科及以上", "experience": "3年以上",
        "duties": "负责持续集成交付、环境治理与发布效率提升。",
        "skills": {
            "2020-2021": ["Jenkins", "Shell", "CI/CD", "Git", "监控"],
            "2022-2023": ["GitLab CI", "Kubernetes", "CI/CD", "Ansible", "监控告警"],
            "2024-2026": ["GitOps", "Kubernetes", "平台工程", "可观测性", "AIOps", "FinOps"],
        },
    },

    # ===================== 新岗位（萌芽年份之后才出现）=====================
    "AI Agent 工程师": {
        "category": "大模型应用", "emerging_year": 2024,
        "salary": (18, 30), "education": "本科及以上", "experience": "2年以上",
        "duties": "负责企业多智能体协作与业务自动化，设计 Agent 工作流、工具调用、记忆与评测体系。",
        "skills": {
            "2024-2026": ["Python", "AI Agent", "LangChain", "LangGraph", "Prompt Engineering", "大模型 API", "MCP", "RAG", "向量数据库", "Docker"],
        },
    },
    "RAG 应用工程师": {
        "category": "大模型应用", "emerging_year": 2024,
        "salary": (16, 26), "education": "本科及以上", "experience": "1年以上",
        "duties": "负责检索增强生成系统建设，优化知识库检索、召回与生成质量。",
        "skills": {
            "2024-2026": ["RAG", "向量数据库", "Embedding", "LangChain", "大模型 API", "知识库", "混合检索", "Python"],
        },
    },
    "提示词工程师": {
        "category": "大模型应用", "emerging_year": 2024,
        "salary": (15, 25), "education": "本科及以上", "experience": "1年以上",
        "duties": "负责提示词设计、评测与优化，提升大模型在业务场景中的输出质量。",
        "skills": {
            "2024-2026": ["Prompt Engineering", "大模型 API", "评测体系", "Few-shot", "思维链", "Python"],
        },
    },
    "大模型可信治理工程师": {
        "category": "大模型应用", "emerging_year": 2024,
        "salary": (17, 28), "education": "硕士及以上", "experience": "2年以上",
        "duties": "负责大模型内容安全、幻觉防控、合规与可解释性治理。",
        "skills": {
            "2024-2026": ["幻觉防控", "内容安全", "合规", "红队评测", "可解释性", "RAG 证据绑定", "Python"],
        },
    },
    "大模型微调工程师": {
        "category": "大模型应用", "emerging_year": 2023,
        "salary": (18, 30), "education": "硕士及以上", "experience": "2年以上",
        "duties": "负责领域大模型的指令微调、对齐与部署优化。",
        "skills": {
            "2022-2023": ["PyTorch", "预训练模型", "指令微调", "模型部署", "Transformers"],
            "2024-2026": ["大模型微调", "LoRA", "QLoRA", "RLHF", "DeepSpeed", "PyTorch", "模型部署"],
        },
    },
    "具身智能算法工程师": {
        "category": "人工智能", "emerging_year": 2025,
        "salary": (20, 35), "education": "硕士及以上", "experience": "2年以上",
        "duties": "负责机器人具身智能的感知、决策与控制算法研发。",
        "skills": {
            "2024-2026": ["具身智能", "强化学习", "模仿学习", "机器人 OS", "多模态大模型", "仿真", "PyTorch"],
        },
    },
    "智能体平台架构师": {
        "category": "大模型应用", "emerging_year": 2025,
        "salary": (22, 38), "education": "硕士及以上", "experience": "5年以上",
        "duties": "负责多智能体平台的整体架构设计与稳定性、可扩展性建设。",
        "skills": {
            "2024-2026": ["多智能体", "分布式系统", "Kubernetes", "大模型编排", "MCP", "可靠性工程", "Python/Go"],
        },
    },
    "多模态算法工程师": {
        "category": "人工智能", "emerging_year": 2025,
        "salary": (19, 32), "education": "硕士及以上", "experience": "2年以上",
        "duties": "负责图文、音视频等多模态模型的研发与落地。",
        "skills": {
            "2024-2026": ["多模态大模型", "CLIP", "扩散模型", "图文生成", "PyTorch", "模型部署"],
        },
    },
    "边缘智能工程师": {
        "category": "物联网", "emerging_year": 2025,
        "salary": (16, 27), "education": "本科及以上", "experience": "2年以上",
        "duties": "负责边缘侧的模型部署、推理优化与云边协同智能。",
        "skills": {
            "2024-2026": ["边缘计算", "模型量化", "端侧推理", "NPU", "云边协同", "C++/Python"],
        },
    },
}


def era_of(year):
    if year <= 2021:
        return "2020-2021"
    if year <= 2023:
        return "2022-2023"
    return "2024-2026"


def salary_of(job, year):
    """月薪随年份通胀：每年约 +4%，并加少量随机波动。"""
    lo, hi = job["salary"]
    inflate = 1.04 ** (year - 2020)
    lo = round(lo * inflate)
    hi = round(hi * inflate)
    return lo, hi


def build_description(job, skills, year, company, city):
    """拼接职位描述：职责 + 必备技能(随机排序) + 加分技能 + 典型场景 + 随机项目亮点。"""
    rskills = list(skills)
    random.shuffle(rskills)  # 技能顺序打乱，制造差异
    required = rskills[: min(len(rskills), 6)]
    bonus = rskills[6:8] if len(rskills) > 6 else []
    scenario = {
        "大模型应用": "智能客服与企业流程自动化",
        "人工智能": "智能推荐与风险识别",
        "大数据": "数据资产与实时分析",
        "智能系统": "端侧智能化与边缘推理",
        "物联网": "设备管理与云边协同",
        "云原生": "容器化与弹性伸缩",
        "后端开发": "高并发交易与业务中台",
    }.get(job["category"], "数字化业务场景")
    desc = job['duties']
    desc += f"要求掌握 {'、'.join(required)}。"
    if bonus:
        desc += f"熟悉 {'、'.join(bonus)} 者优先。"
    desc += f"典型场景为{scenario}。"
    desc += f" 需{random.choice(PROJECT_PHRASES)}。"
    desc += " 候选人需具备良好的协作沟通能力，参与核心业务场景交付，并对关键指标进行量化复盘。"
    return desc


def generate(target, seed):
    random.seed(seed)
    rows = []
    year_range = list(range(2020, 2027))

    # 先收集所有 (job, year) 组合
    combos = []
    for name, job in JOBS.items():
        for year in year_range:
            if job["emerging_year"] is None or year >= job["emerging_year"]:
                combos.append((name, job, year))

    # 每条组合的基础条数：让总数逼近 target；新岗位首年、近一年稍多
    base = target // len(combos)
    remainder = target % len(combos)

    for idx, (name, job, year) in enumerate(combos):
        n = base + (1 if idx < remainder else 0)
        # 新岗位萌芽首年条数略少，后续年份略多，形成“增长曲线”
        if job["emerging_year"] is not None:
            age = year - job["emerging_year"]
            n = max(3, int(n * (0.6 + 0.25 * age)))
        for _ in range(n):
            rows.append(build_row(name, job, year))

    # 补足到至少 target 条（新岗位首年加权会略降总数）
    while len(rows) < target:
        name, job, year = random.choice(combos)
        rows.append(build_row(name, job, year))

    # ---- 脏数据注入 ----
    # 1) 重复/抄袭：随机复制约 3% 的记录，换一个来源平台（保留原内容）
    n_dup = int(len(rows) * 0.03)
    for _ in range(n_dup):
        src = random.choice(rows)
        dup = dict(src)
        dup["来源平台"] = random.choice(PLATFORMS)
        dup["来源"] = dup["来源平台"]
        dup["企业名称"] = random.choice(COMPANIES)
        rows.append(dup)

    # 2) 噪音：约 2% 的记录制造缺字段/格式错乱
    n_noise = int(len(rows) * 0.02)
    for _ in range(n_noise):
        i = random.randrange(len(rows))
        r = rows[i]
        op = random.random()
        if op < 0.34:
            r["工作区域"] = ""
        elif op < 0.67:
            r["要求经验"] = ""
        else:
            r["最低月薪"] = random.choice(["", "面议", str(random.randint(5, 40))])

    # 3) 时滞：约 1.5% 的记录在 2025-2026 年仍使用旧时代技能（技能描述滞后）
    n_stale = int(len(rows) * 0.015)
    for _ in range(n_stale):
        i = random.randrange(len(rows))
        r = rows[i]
        if r["招聘发布年份"] in ("2025", "2026"):
            r["职位描述"] = build_description(
                JOBS.get(r["招聘岗位"], JOBS["Java 开发工程师"]),
                [], int(r["招聘发布年份"]),
                r["企业名称"], r["工作城市"],
            ).replace("要求掌握", "要求掌握 Java、Spring MVC、MyBatis、MySQL、Redis、Tomcat，")

    # ---- 2019 基线年（独立种子，追加在末尾，避免漂移 2020-2026 随机序列）----
    random.seed(seed + 999)
    for name, job in JOBS.items():
        if job["emerging_year"] is None:
            for _ in range(78):
                rows.append(build_row(name, job, 2019))

    return rows


def build_row(name, job, year):
    company = random.choice(COMPANIES)
    city, area, workloc = random.choice(CITIES)
    skills = job["skills"][era_of(year)]
    lo, hi = salary_of(job, year)
    # 高/中/初级岗位的薪资与经验微调
    level = random.choices(["", "中级", "高级"], weights=[4, 3, 3])[0]
    if level == "高级":
        lo, hi = lo + 5, hi + 6
        exp = job["experience"]
    elif level == "中级":
        lo, hi = lo + 2, hi + 3
        exp = "3年以上" if job["experience"] in ("5年以上", "8年以上") else job["experience"]
    else:
        exp = job["experience"]
    title = (level + name) if level else name

    # 日期：发布日期在该年内随机，结束日期滞后 30-60 天
    month = random.randint(1, 12)
    day = random.randint(1, 28)
    try:
        pub = datetime.date(year, month, day)
    except ValueError:
        pub = datetime.date(year, month, 1)
    end = pub + datetime.timedelta(days=random.randint(30, 60))
    # 结束日期若跨年，结束年份也相应变化
    end_year = end.year

    desc = build_description(job, skills, year, company, city)
    platform = random.choice(PLATFORMS)

    return {
        "人工智能关键词": job["category"],
        "企业名称": company,
        "招聘岗位": title,
        "工作城市": city,
        "工作区域": area,
        "最低月薪": str(lo),
        "最高月薪": str(hi),
        "职位描述": desc,
        "学历要求": job["education"],
        "要求经验": exp,
        "招聘人数": str(random.randint(1, 10)),
        "招聘类别": random.choice(RECRUIT_TYPE),
        "初级分类": job["category"],
        "来源平台": platform,
        "公司地点": city,
        "工作地点": workloc,
        "招聘发布日期": pub.strftime("%Y-%m-%d"),
        "招聘结束日期": end.strftime("%Y-%m-%d"),
        "招聘发布年份": str(year),
        "招聘结束年份": str(end_year),
        "来源": platform,
    }


def main():
    target = int(sys.argv[1]) if len(sys.argv) > 1 else 10000
    seed = int(sys.argv[2]) if len(sys.argv) > 2 else 42
    rows = generate(target, seed)

    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data")
    out_path = os.path.normpath(os.path.join(out_dir, "generated-jobs-10000.csv"))
    os.makedirs(out_dir, exist_ok=True)

    fields = ["人工智能关键词", "企业名称", "招聘岗位", "工作城市", "工作区域", "最低月薪", "最高月薪",
              "职位描述", "学历要求", "要求经验", "招聘人数", "招聘类别", "初级分类", "来源平台",
              "公司地点", "工作地点", "招聘发布日期", "招聘结束日期", "招聘发布年份", "招聘结束年份", "来源"]

    with open(out_path, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

    print("已生成 %d 条记录 -> %s" % (len(rows), out_path))
    # 简单统计
    from collections import Counter
    yc = Counter(r["招聘发布年份"] for r in rows)
    tc = Counter(r["招聘岗位"].replace("高级", "").replace("中级", "") for r in rows)
    print("年份分布:", dict(sorted(yc.items())))
    print("岗位种类数:", len(tc))
    print("前15岗位:", tc.most_common(15))


if __name__ == "__main__":
    main()
