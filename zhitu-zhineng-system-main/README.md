# 职途智配——基于可信智能体的岗位能力图谱构建与人岗匹配诊断系统

“职途智配”是面向挑战杯赛题 XH-202621 的完整前后端工程，覆盖多源数据治理、新岗位发现与定义、既有岗位能力动态演化、技能点级全景图谱、简历解析、人岗匹配、差距诊断、学习路径、可信审核、证据溯源及六智能体协同问答。

## 技术栈

- 后端：Java 17、Spring Boot 3.5.16、Maven、Spring JDBC、MySQL 8、H2、Redis、Apache Tika、Jsoup、OpenAI-Compatible API
- 前端：Vue 3、TypeScript、Vite、Pinia、Vue Router、Element Plus、ECharts、GSAP、Lucide
- 部署：Docker Compose、Nginx
- 测试：JUnit 5、JaCoCo、Python 指标脚本、20 条岗位 JD 与金标准样本

## 六智能体

1. 数据治理智能体：CSV、PDF、Word、HTML、文本和公开网页采集，清洗、重复检测、时滞和质量评分。
2. 岗位洞察智能体：JD 解析、新岗位发现、既有岗位技能新增/弱化/修改分析。
3. 能力图谱与演化智能体：岗位—技能—技术栈—等级关系及证据可视化，以及岗位能力动态演化。
4. 画像匹配智能体：简历解析、五维匹配和技能差距诊断。
5. 学习规划智能体：依据缺失技能、前置关系、周数和工时生成学习路径。
6. 可信审核智能体：多源交叉验证、置信度、幻觉风险、人工修改/通过/驳回和审计记录。

## Docker 一键启动

```bash
cp .env.example .env
docker compose up --build
```

前端访问 `http://localhost:5173`，后端健康检查为 `http://localhost:8080/actuator/health`。

## IDEA 本地启动

1. IDEA 打开 `backend/pom.xml`，项目 SDK 选择 JDK 17 并重新加载 Maven。
2. 运行 `com.zhitu.ZhituApplication`；默认使用 H2 内存数据库。
3. 在 `frontend` 目录执行 `npm install`、`npm run dev`。
4. 浏览器访问 `http://localhost:5173`。

首次后端启动会导入 120 条 JD，保留并标注重复证据，然后生成图谱、候选岗位、演化事件、演示匹配结果与学习路径。更详细步骤见 `docs/IDEA_中文版部署指南.md`。

## 测试与验收

```bash
python scripts/validate_project.py
cd backend && mvn clean verify
cd ../frontend && npm install && npm run type-check && npm run build
```

当前离线指标：JD 技能抽取 F1 = 1.0000，简历技能抽取 F1 = 0.9744。匹配准确率必须基于专家标注样本计算，工程没有伪造该指标。完整说明见 `docs/VALIDATION_REPORT.md`。

## 关键文档

- `docs/ARCHITECTURE.md`：系统架构与可信机制
- `docs/FEATURE_MATRIX.md`：赛题要求—功能映射
- `docs/API.md`：后端接口清单
- `docs/TEST_PLAN.md`：测试方案与指标口径
- `docs/DEMO_SCRIPT.md`：10 分钟演示脚本
- `docs/IDEA_中文版部署指南.md`：Windows/IDEA 操作步骤

## 大模型配置

问答模块采用“治理数据检索 + 大模型生成”的证据问答链路：先从 `zhitu_governed_job`、`zhitu_governed_job_skill` 和业务分析表中检索证据，再将证据交给 OpenAI-Compatible 模型生成回答。回答中的 `[证据N]` 与前端“查看数据证据”列表一一对应。

在 `.env` 中至少填写：

```dotenv
AI_ENABLED=true
AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_API_KEY=your_ai_api_key_here
AI_MODEL=qwen-plus
```

密钥变量兼容 `AI_API_KEY`、`DASHSCOPE_API_KEY` 和 `ALI_API_KEY`，按此前顺序读取；只需配置其中一个。检测到密钥后会默认启用模型，也可用 `AI_ENABLED=false` 显式关闭。未配置密钥或模型调用失败时，接口会明确降级为“检索模式”，不会再用写死的兜底回答伪装成模型结果。`sessionId` 用于保存最近 6 条对话，支持基于证据的连续追问。

图片简历解析采用“多模态读图优先 + OCR 兜底 + 原文证据门禁”。DeepSeek 文本模型用于结构化校准；如果要让系统直接理解图片版面，需要额外配置一个 OpenAI-Compatible 视觉模型端点：

```dotenv
AI_RESUME_VISION_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_RESUME_VISION_API_KEY=your_qwen_vl_api_key_here
AI_RESUME_VISION_MODEL=qwen-vl-plus
```

若视觉模型未配置，系统会在页面中标记“等待多模态视觉模型”，并自动走 OCR/样本校准兜底，不再把文本模型误当成图片模型。

## 百万历史数据：年度新岗位预测与回测（V3）

系统可直接连接已有 `career_data_governance.dataset_job_raw` 百万级 MySQL 原始岗位库，不迁移、不删除原始数据。

- 固定抽取 2026 年 1000 条作为最终 holdout 测试集；同一 seed 可复现。
- 其余数据均保留在训练池。
- 执行 2020→2021、2021→2022 …… 的严格年度滚动回测。
- 2025→2026 只用固定 1000 条测试集计算最终指标，防止测试污染。
- 前端新增“验证”页面，展示年度样本、Precision@K、Recall、F1、Calibration、Trust Score 和每个候选的证据。
- 实验结果写入独立 `zhitu_*` 表，不覆盖原业务表。

详细配置与指标口径见 `docs/年度新岗位预测与验证指南.md`。

## V4：百万历史 JD 连续治理

V4 不再假设 `dataset_job_raw` 一定具有 `title/company/published_at` 英文字段。系统会从 `information_schema` 自动识别：

- `招聘岗位 / 岗位名称 / title / job_title`
- `企业名称 / 公司名称 / company`
- `职位描述 / 岗位描述 / description`
- `招聘发布日期 / 发布日期 / published_at`
- `招聘发布年份 / 发布年份 / published_year`

运行顺序：

1. IDEA 启动后端。
2. 打开前端“解析”页面。
3. 点击“开始连续治理”。后端会先固定 2026 年 1000 条 holdout。
4. 其余 `dataset_job_raw` 记录按 ID 每批 1000 条连续治理。
5. 可以“安全暂停”，下次“从断点继续”。
6. 治理完成后再进入“验证”页面运行 2020→2026 年度滚动回测。

全量阶段采用确定性规则、技能词典、质量评分和模板指纹，不逐条调用大模型；这样百万级任务可复现、成本可控。低置信结果仍可在可信审核阶段进一步使用大模型复核。

原始表不会被 UPDATE/DELETE。派生结果写入：

- `zhitu_governed_job`
- `zhitu_governed_job_skill`
- `zhitu_governance_issue`
- `zhitu_governance_run`
- `zhitu_duplicate_cluster`
- `zhitu_temporal_holdout`

## V5：百万治理数据全系统联动

V5 将 `zhitu_governed_job` 与 `zhitu_governed_job_skill` 作为岗位市场分析主数据源，完成总览、数据、探新、验证、演化和图谱的全链路联动。

- JD 解析页“最近治理结果抽样”和“系统内即时解析记录”增加独立滚动容器与吸顶表头。
- 总览“完整业务闭环”移动到技术栈分布之前，01~06 可直接跳转对应页面。
- 探新结果完整输出：岗位名称、核心职责、必备技能、加分技能、典型行业应用场景。
- 新岗位、能力演化结果由百万治理数据重新计算，并继续镜像到 H2 审核表，保持人工审核兼容。
- 年度验证展示原始训练池、已治理和有效训练数量，训练仅使用治理后 `valid_for_analysis=1` 数据。
- 图谱直接根据治理后岗位—技能证据动态构建，重复模板按 `duplicate_weight` 降权。

详细说明见 `docs/V5百万治理数据全系统联动说明.md`；逐文件完整源码见 `docs/V5修改文件完整代码.md`。
