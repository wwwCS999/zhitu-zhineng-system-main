# V5 Modification Manifest

本补丁面向已经完成 V4 百万 JD 治理的项目，核心目标是让治理结果成为总览、数据、探新、验证、演化、图谱的统一岗位市场数据源，并补齐新岗位五项定义及前端滚动交互。

修改文件：

- backend/src/main/java/com/zhitu/service/DashboardService.java
- backend/src/main/java/com/zhitu/service/EmergingRoleService.java
- backend/src/main/java/com/zhitu/service/EvolutionService.java
- backend/src/main/java/com/zhitu/service/GraphService.java
- backend/src/main/java/com/zhitu/service/TemporalDatasetService.java
- backend/src/main/java/com/zhitu/service/AgentOrchestratorService.java
- frontend/src/views/DashboardView.vue
- frontend/src/views/GovernanceView.vue
- frontend/src/views/JobParsingView.vue
- frontend/src/views/EmergingView.vue
- frontend/src/views/TemporalForecastView.vue
- frontend/src/views/EvolutionView.vue
- frontend/src/views/GraphView.vue
- frontend/src/styles/global.css
- docs/V5百万治理数据全系统联动说明.md
- docs/V5修改文件完整代码.md
- README.md

V5 不要求重新治理已经处理完的百万 JD；覆盖后直接复用 MySQL `zhitu_governed_job` / `zhitu_governed_job_skill`。
