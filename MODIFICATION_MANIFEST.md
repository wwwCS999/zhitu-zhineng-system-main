# 百万数据年度预测 V3 修改清单

以下文件均为完整版本，可直接按相同相对路径覆盖。

## 后端

- `backend/src/main/java/com/zhitu/repository/RawDatabaseClient.java`
- `backend/src/main/java/com/zhitu/service/TemporalDatasetService.java`
- `backend/src/main/java/com/zhitu/service/TemporalForecastService.java`
- `backend/src/main/java/com/zhitu/controller/TemporalForecastController.java`
- `backend/src/main/java/com/zhitu/dto/Requests.java`
- `backend/src/main/java/com/zhitu/service/DashboardService.java`
- `backend/src/main/resources/application.yml`

## 数据库

- `database/temporal_forecast_upgrade.sql`

## 前端

- `frontend/src/api/index.ts`
- `frontend/src/router/index.ts`
- `frontend/src/layout/AppShell.vue`
- `frontend/src/components/AppIcon.vue`
- `frontend/src/views/TemporalForecastView.vue`
- `frontend/src/styles/global.css`

## 文档

- `docs/年度新岗位预测与验证指南.md`
- `README.md`
- `.env.example`

建议优先使用补丁 ZIP 覆盖，避免逐个复制时漏文件。
