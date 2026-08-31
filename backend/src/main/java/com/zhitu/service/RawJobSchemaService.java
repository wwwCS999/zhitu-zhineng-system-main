package com.zhitu.service;

import com.zhitu.repository.RawDatabaseClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 自动识别 dataset_job_raw 的真实字段。
 *
 * 用户历史数据来自多个 CSV，表头并不统一，因此不能再硬编码 title/company/published_at。
 * 本服务会从 information_schema 读取真实列，并映射为系统统一字段。
 */
@Service
public class RawJobSchemaService {

    private final RawDatabaseClient raw;
    private volatile SchemaMapping cached;

    public RawJobSchemaService(RawDatabaseClient raw) {
        this.raw = raw;
    }

    public SchemaMapping resolve() {
        SchemaMapping current = cached;
        if (current != null) return current;
        if (!raw.ping()) {
            throw new IllegalStateException("无法连接历史岗位 MySQL");
        }
        if (!raw.tableExists(raw.rawTable())) {
            throw new IllegalStateException("数据库中不存在原始岗位表：" + raw.rawTable());
        }

        List<Map<String, Object>> rows = raw.list(
                "SELECT column_name, data_type FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position",
                raw.rawTable()
        );
        Map<String, ColumnMeta> columns = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("column_name"));
            String type = String.valueOf(row.get("data_type"));
            columns.put(normalize(name), new ColumnMeta(name, type));
        }

        String id = find(columns, "id", "raw_id", "job_id", "数据id", "数据_id");
        if (id == null) {
            throw new IllegalStateException("原始岗位表必须有唯一 ID 字段。建议字段名为 id。");
        }

        String title = find(columns,
                "title", "job_title", "job_title_raw", "position_title", "position_name", "position", "job_name",
                "招聘岗位", "岗位名称", "职位名称", "职位", "岗位", "招聘职位");
        String company = find(columns,
                "company", "company_name", "enterprise_name", "employer",
                "企业名称", "公司名称", "招聘单位", "单位名称");
        String description = find(columns,
                "description", "job_description", "job_desc", "job_detail", "job_content", "content", "jd", "detail",
                "职位描述", "岗位描述", "工作描述", "招聘描述", "职位详情", "岗位职责");
        String city = find(columns,
                "city", "work_city", "job_city", "工作城市", "城市", "工作地点", "公司地点");
        String education = find(columns,
                "education", "education_requirement", "学历要求", "学历", "最低学历");
        String experience = find(columns,
                "experience", "experience_requirement", "work_experience", "要求经验", "经验要求", "工作经验");
        String source = find(columns,
                "source", "source_platform", "platform", "来源", "来源平台", "数据来源");
        String industry = find(columns,
                "industry", "industry_name", "行业", "所属行业", "招聘类别", "初级分类");
        String salaryMin = find(columns,
                "salary_min", "min_salary", "最低月薪", "最低薪资", "薪资下限");
        String salaryMax = find(columns,
                "salary_max", "max_salary", "最高月薪", "最高薪资", "薪资上限");
        String publishedAt = find(columns,
                "published_at", "publish_at", "publish_time", "publish_date", "published_date", "posting_date", "job_publish_date", "release_date",
                "招聘发布日期", "发布日期", "发布时间", "发布日", "招聘时间");
        String publishedYear = find(columns,
                "published_year", "publish_year", "recruitment_year", "year", "posting_year",
                "招聘发布年份", "发布年份", "招聘年份", "年份");

        if (title == null) {
            throw new IllegalStateException(
                    "无法识别岗位名称字段。当前支持 title/job_title/招聘岗位/岗位名称/职位名称 等字段。"
            );
        }
        if (description == null) {
            throw new IllegalStateException(
                    "无法识别职位描述字段。当前支持 description/job_description/职位描述/岗位描述 等字段。"
            );
        }
        if (publishedAt == null && publishedYear == null) {
            throw new IllegalStateException(
                    "无法识别岗位年份。请确认原表至少存在 招聘发布日期/发布日期/published_at，" +
                            "或 招聘发布年份/published_year 字段。"
            );
        }

        SchemaMapping resolved = new SchemaMapping(
                id, title, company, description, city, education, experience,
                source, industry, salaryMin, salaryMax, publishedAt, publishedYear,
                new ArrayList<>(rows.stream().map(r -> String.valueOf(r.get("column_name"))).toList())
        );
        cached = resolved;
        return resolved;
    }

    public Map<String, Object> describe() {
        SchemaMapping s = resolve();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("table", raw.rawTable());
        result.put("id", s.id());
        result.put("title", s.title());
        result.put("company", s.company());
        result.put("description", s.description());
        result.put("city", s.city());
        result.put("education", s.education());
        result.put("experience", s.experience());
        result.put("source", s.source());
        result.put("industry", s.industry());
        result.put("salaryMin", s.salaryMin());
        result.put("salaryMax", s.salaryMax());
        result.put("publishedAt", s.publishedAt());
        result.put("publishedYear", s.publishedYear());
        result.put("allColumns", s.allColumns());
        result.put("ready", true);
        return result;
    }

    public String idExpr(String alias) {
        return columnExpr(alias, resolve().id(), "NULL");
    }

    public String titleExpr(String alias) {
        return textExpr(alias, resolve().title());
    }

    public String companyExpr(String alias) {
        return textExpr(alias, resolve().company());
    }

    public String descriptionExpr(String alias) {
        return textExpr(alias, resolve().description());
    }

    public String cityExpr(String alias) {
        return textExpr(alias, resolve().city());
    }

    public String educationExpr(String alias) {
        return textExpr(alias, resolve().education());
    }

    public String experienceExpr(String alias) {
        return textExpr(alias, resolve().experience());
    }

    public String sourceExpr(String alias) {
        return textExpr(alias, resolve().source());
    }

    public String industryExpr(String alias) {
        return textExpr(alias, resolve().industry());
    }

    public String salaryMinExpr(String alias) {
        return numericExpr(alias, resolve().salaryMin());
    }

    public String salaryMaxExpr(String alias) {
        return numericExpr(alias, resolve().salaryMax());
    }

    /**
     * 返回可以直接放进 MySQL SQL 的“统一发布日期”表达式。
     * 优先使用真实日期字段；只有年份时生成该年 01-01，仅用于年度切分。
     */
    public String publishedDateExpr(String alias) {
        SchemaMapping s = resolve();
        if (s.publishedAt() != null) {
            String c = q(alias, s.publishedAt());
            String yearFallback = "NULL";
            if (s.publishedYear() != null) {
                String y = q(alias, s.publishedYear());
                yearFallback = "STR_TO_DATE(CONCAT(CAST(SUBSTRING(TRIM(CAST(" + y + " AS CHAR)),1,4) AS UNSIGNED),'-01-01'),'%Y-%m-%d')";
            }
            return "COALESCE(" +
                    "DATE(" + c + ")," +
                    "STR_TO_DATE(SUBSTRING(TRIM(CAST(" + c + " AS CHAR)),1,10),'%Y-%m-%d')," +
                    "STR_TO_DATE(SUBSTRING(TRIM(CAST(" + c + " AS CHAR)),1,10),'%Y/%m/%d')," +
                    yearFallback +
                    ")";
        }
        return "STR_TO_DATE(CONCAT(" + publishedYearExpr(alias) + ",'-01-01'),'%Y-%m-%d')";
    }

    /**
     * 年份优先读取独立年份字段，避免历史表只有“招聘发布年份”时无法回测。
     */
    public String publishedYearExpr(String alias) {
        SchemaMapping s = resolve();
        if (s.publishedYear() != null) {
            String c = q(alias, s.publishedYear());
            return "CAST(SUBSTRING(TRIM(CAST(" + c + " AS CHAR)),1,4) AS UNSIGNED)";
        }
        return "YEAR(" + publishedDateExpr(alias) + ")";
    }

    public String selectProjection(String alias) {
        return idExpr(alias) + " AS raw_id," +
                titleExpr(alias) + " AS raw_title," +
                companyExpr(alias) + " AS company," +
                descriptionExpr(alias) + " AS description," +
                cityExpr(alias) + " AS city," +
                educationExpr(alias) + " AS education," +
                experienceExpr(alias) + " AS experience," +
                sourceExpr(alias) + " AS source_name," +
                industryExpr(alias) + " AS industry," +
                salaryMinExpr(alias) + " AS salary_min," +
                salaryMaxExpr(alias) + " AS salary_max," +
                publishedDateExpr(alias) + " AS published_at," +
                publishedYearExpr(alias) + " AS published_year";
    }

    private String textExpr(String alias, String column) {
        if (column == null) return "''";
        return "COALESCE(TRIM(CAST(" + q(alias, column) + " AS CHAR)),'')";
    }

    private String numericExpr(String alias, String column) {
        if (column == null) return "NULL";
        String c = q(alias, column);
        return "CAST(NULLIF(REGEXP_REPLACE(CAST(" + c + " AS CHAR),'[^0-9.]',''),'') AS DECIMAL(12,2))";
    }

    private String columnExpr(String alias, String column, String fallback) {
        return column == null ? fallback : q(alias, column);
    }

    private String q(String alias, String column) {
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        return prefix + "`" + column.replace("`", "``") + "`";
    }

    private String find(Map<String, ColumnMeta> columns, String... candidates) {
        for (String candidate : candidates) {
            ColumnMeta meta = columns.get(normalize(candidate));
            if (meta != null) return meta.name();
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "")
                .trim();
    }

    private record ColumnMeta(String name, String type) {}

    public record SchemaMapping(
            String id,
            String title,
            String company,
            String description,
            String city,
            String education,
            String experience,
            String source,
            String industry,
            String salaryMin,
            String salaryMax,
            String publishedAt,
            String publishedYear,
            List<String> allColumns
    ) {}
}
