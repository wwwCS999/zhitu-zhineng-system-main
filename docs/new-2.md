# 年度新岗位预测与验证指南

## 1. 目标

本模块直接读取现有 MySQL 数据库 `career_data_governance.dataset_job_raw`，不复制、不删除百万级原始岗位数据。

实验采用严格的年度滚动验证：

- 2020 年岗位 -> 预测 2021 年萌芽/新岗位 -> 用 2021 年真实岗位验证
- 2021 年岗位 -> 预测 2022 年 -> 用 2022 年验证
- ...
- 2025 年岗位 -> 预测 2026 年 -> **只用固定的 2026 年 1000 条 holdout 测试数据验证**

固定 1000 条测试数据只记录原始 `id`，原表一条数据都不会被删除或移动。除这 1000 条外，其余记录均属于训练池。历史回测时，为避免未来信息泄漏，每个窗口只使用训练年份 t 的数据建模，t+1 仅用于验证。

## 2. 数据字段要求

`dataset_job_raw` 至少需要：

```text
id
 title
 company
 published_at
```

系统会自动检查。如果 `published_at` 为空，该行仍保留在原始数据库和全局训练池统计中，但因为无法确定年份，不能进入年度时序实验。建议优先补齐发布日期。

## 3. 先配置 MySQL

修改：

```text
backend/src/main/resources/application.yml
```

默认：

```yaml
app:
  raw-database:
    url: jdbc:mysql://localhost:3306/career_data_governance?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: 123456
    table: dataset_job_raw
    holdout-year: 2026
    holdout-size: 1000
    holdout-seed: zhitu-2026-v1
```

如果你的 MySQL 密码不是 `123456`，只需要修改 `password`。

也可以使用环境变量：

```text
RAW_DB_URL
RAW_DB_USERNAME
RAW_DB_PASSWORD
RAW_DB_TABLE
RAW_HOLDOUT_YEAR
RAW_HOLDOUT_SIZE
RAW_HOLDOUT_SEED
```

## 4. 强烈建议给 published_at 建索引

因为数据量已经达到百万级，年度统计会频繁使用 `published_at`。

先在 Navicat 执行：

```sql
SHOW INDEX FROM dataset_job_raw;
```

如果没有 `published_at` 索引，再执行：

```sql
ALTER TABLE dataset_job_raw
ADD INDEX idx_zhitu_published_at (published_at);
```

注意：百万级、GB 级表创建索引可能需要数分钟并占用额外磁盘空间，所以系统不会自动执行这个 ALTER TABLE。

## 5. 新增实验表

系统首次打开“年度预测验证”页面时会自动建立：

```text
zhitu_temporal_holdout
zhitu_forecast_run
zhitu_forecast_candidate
```

也可手工执行：

```text
database/temporal_forecast_upgrade.sql
```

这些表都以 `zhitu_` 开头，不会覆盖现有业务表。

## 6. 2026 年 1000 条测试集

点击前端：

```text
验证 -> 锁定 2026 测试集
```

抽样是确定性的：

```text
ORDER BY CRC32(CONCAT(id, ':', seed)), id LIMIT 1000
```

默认 seed：

```text
zhitu-2026-v1
```

因此同一数据库、同一 seed 重复运行得到相同 1000 条，避免为了提高指标而不断换测试集。

## 7. 训练和验证口径

### 全局数据划分

```text
TEST = 2026 固定 1000 条
TRAIN_POOL = 其他全部岗位数据
```

### 年度回测

```text
Train(2020) -> Test(2021 full year)
Train(2021) -> Test(2022 full year)
Train(2022) -> Test(2023 full year)
Train(2023) -> Test(2024 full year)
Train(2024) -> Test(2025 full year)
Train(2025) -> Test(2026 fixed 1000)
```

注意：所谓“其他数据全部用于训练”并不意味着做 2020->2021 时可以读取 2022、2023 等未来数据。未来数据只能在轮到它所属年份时成为新的训练窗口，否则会造成时间泄漏。

## 8. 新岗位预测模型

当前提供的是可解释、可复现的百万数据基线模型，针对“正在萌芽但尚未标准化”的岗位，而不是声称可以凭空预测一个此前完全未出现过的岗位名称。

每个训练年份按标准化岗位标题聚合：

- `sample_count`：岗位样本支持数
- `company_count`：独立企业覆盖
- `h1_count`：上半年出现次数
- `h2_count`：下半年出现次数
- `previous_count`：上一年出现次数

预测分：

```text
forecastScore =
0.34 * novelty
+ 0.28 * momentum
+ 0.20 * support
+ 0.18 * companyDiversity
```

其中：

- novelty：相对上一年的岗位新颖度
- momentum：下半年相对上半年的增长动量
- support：样本量证据
- companyDiversity：企业覆盖，降低单一公司异常招聘影响

证据可信度：

```text
confidence =
0.38 * support
+ 0.34 * companyDiversity
+ 0.18 * halfYearBalance
+ 0.10 * sampleFloor
```

该设计与现有系统的“多源/多企业交叉验证、证据溯源、幻觉防控”机制兼容。

## 9. 验证指标

### Precision@K（页面称“预测准确率”）

Top-K 预测中，有多少候选在下一年真实出现并达到新兴/增长条件。

### Recall

下一年真实萌芽岗位中，有多少被预测覆盖。

### F1

Precision 与 Recall 的调和平均。

### Avg Similarity

预测标题与下一年真实岗位标题的平均匹配相似度。

### Calibration

使用预测分的 Brier calibration：

```text
Calibration = 1 - mean((forecastScore - hit)^2)
```

### Trust Score（可信度）

```text
Trust =
0.42 * Precision
+ 0.20 * Calibration
+ 0.18 * AvgSimilarity
+ 0.20 * AvgEvidenceConfidence
```

这不是“模型自己说自己可信”，而是把实际命中表现、概率校准、真实标题匹配和证据充分度共同纳入。

## 10. 前端操作

启动后访问：

```text
http://localhost:5173/forecast
```

推荐顺序：

1. 查看年度数据量。
2. 若“发布日期索引”为“建议添加”，先在 Navicat 建索引。
3. 点击“锁定 2026 测试集”。
4. 确认 2026 测试数为 `1000 / 1000`。
5. 默认设置 `2020 -> 2026`、Top-K 30、最低支持 3。
6. 点击“运行年度预测与回测”。
7. 点击任一年度卡片查看候选明细和真实匹配证据。

## 11. API

```text
GET  /api/temporal/overview
GET  /api/temporal/years
POST /api/temporal/holdout/prepare
GET  /api/temporal/holdout/sample
POST /api/temporal/backtest
GET  /api/temporal/runs
GET  /api/temporal/runs/{runId}
```

## 12. 重要边界

- 2026 的 1000 条 holdout 只用于最终测试，不能参与 2025->2026 的预测计算。
- 2026 剩余数据仍在训练池中，可用于未来的 2026->2027 模型。
- `published_at IS NULL` 的数据无法按年份划分，需补日期后才能进入年度实验。
- 当前年度预测是可解释统计基线。后续可在不改变测试划分的前提下替换成 LightGBM、时序 Transformer 或 LLM+图谱模型，并用同一 holdout 公平比较。
