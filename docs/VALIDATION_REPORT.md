# 构建与验收报告

生成日期：2026-08-01

## 已完成的离线验证

- 工程必需文件、JSON 和 CSV 结构检查通过。
- 测试数据包含 **120 条岗位 JD**；重复和近重复 JD 采用“保留记录、标记重复组、降低证据权重”的策略，不再直接丢弃岗位记录。
- 后端 42 个主源码 Java 文件通过 Java 17 语法与内部类型静态编译；共生成 52 个 class 文件（外部框架使用签名桩，仅用于源码级验收）。
- 规则引擎冒烟测试通过：文本标准化、可信度、新岗位评分和人岗匹配均返回有效结果。
- 前端 22 个 TypeScript/Vue 脚本通过 TypeScript 转译语法检查，全部 Vue 模板通过标签结构检查。
- `data/evaluate_metrics.py` 运行结果：JD 技能抽取 F1 = 1.0000；简历技能抽取 F1 = 0.9744。

## 需要在本机联网环境执行的验证

当前生成环境没有 Maven 命令，npm 被内部依赖镜像限制，因此未在此环境下载 Spring Boot、Vue、Vite 等第三方依赖。请在本机执行：

```bash
cd backend
mvn clean verify

cd ../frontend
npm install
npm run type-check
npm run build
```

`mvn clean verify` 会执行 JUnit 与 JaCoCo 60% 行覆盖率门槛。最终参赛提交前，应保存 `backend/target/site/jacoco/index.html` 截图或报告。

## 指标边界

JD 与简历抽取指标来自仓库内金标准样本。人岗匹配准确率没有伪造数值；需要由专家对匹配样本标注“合适/基本合适/不合适”或分数区间后，再计算 Accuracy、MAE 和混淆矩阵。
